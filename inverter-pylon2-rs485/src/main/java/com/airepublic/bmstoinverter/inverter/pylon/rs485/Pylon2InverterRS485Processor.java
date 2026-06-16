/**
 * This software is free to use and to distribute in its unchanged form for private use.
 * Commercial use is prohibited without an explicit license agreement of the copyright holder.
 * Any changes to this software must be made solely in the project repository at https://github.com/ai-republic/bms-to-inverter.
 * The copyright holder is not liable for any damages in whatever form that may occur by using this software.
 *
 * (c) Copyright 2022 and onwards - Torsten Oltmanns
 *
 * @author Torsten Oltmanns - bms-to-inverter''AT''gmail.com
 */
package com.airepublic.bmstoinverter.inverter.pylon.rs485;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.airepublic.bmstoinverter.core.Inverter;
import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.core.bms.data.BatteryPack;
import com.airepublic.bmstoinverter.protocol.rs485.JSerialCommPort;

/**
 * Modbus RTU slave processor for Deye inverters. The Deye BMS port sends binary Modbus RTU
 * queries (function 0x11 Report Slave ID and 0x04 Read Input Registers at 0x1000) to addresses
 * 0x00-0x0E. This processor responds to one address per physical battery pack with individual
 * pack data so the Deye can display and average each pack correctly.
 */
@ApplicationScoped
public class Pylon2InverterRS485Processor extends Inverter {
    private final static Logger LOG = LoggerFactory.getLogger(Pylon2InverterRS485Processor.class);

    // Deye BMS RS485 input register map starting at 0x1000 (23 registers)
    private static final int REG_START = 0x1000;
    private static final int REG_COUNT = 23;

    @Override
    protected ByteBuffer readRequest(final Port port) throws IOException {
        final JSerialCommPort serialPort = (JSerialCommPort) port;
        serialPort.ensureOpen();
        final long deadline = System.currentTimeMillis() + 3000;

        while (System.currentTimeMillis() < deadline) {
            // Read one byte: Modbus address
            final byte[] addrBuf = new byte[1];
            if (serialPort.readBytes(addrBuf, 400) == -1) {
                continue;
            }

            final byte address = addrBuf[0];

            // Deye only polls addresses 0x00-0x0E; skip anything outside that range
            if ((address & 0xFF) > 0x0E) {
                continue;
            }

            // Read function code
            final byte[] funcBuf = new byte[1];
            if (serialPort.readBytes(funcBuf, 100) == -1) {
                continue;
            }

            final byte function = funcBuf[0];

            // Read the rest of the frame based on function code
            final byte[] rest;
            if (function == (byte) 0x11) {
                rest = new byte[2]; // CRC lo + CRC hi
            } else if (function == (byte) 0x04) {
                rest = new byte[6]; // reg hi + reg lo + count hi + count lo + CRC lo + CRC hi
            } else {
                continue; // unknown function, skip
            }

            if (serialPort.readBytes(rest, 100) == -1) {
                continue;
            }

            // Validate CRC
            final byte[] frameData = new byte[2 + rest.length - 2];
            frameData[0] = address;
            frameData[1] = function;
            System.arraycopy(rest, 0, frameData, 2, rest.length - 2);
            final int expectedCRC = calculateCRC(frameData, frameData.length);
            final int actualCRC = (rest[rest.length - 2] & 0xFF) | ((rest[rest.length - 1] & 0xFF) << 8);

            if (expectedCRC != actualCRC) {
                LOG.debug("CRC mismatch for addr=0x{} func=0x{}: expected=0x{} actual=0x{}",
                        Integer.toHexString(address & 0xFF),
                        Integer.toHexString(function & 0xFF),
                        Integer.toHexString(expectedCRC),
                        Integer.toHexString(actualCRC));
                continue;
            }

            // Build complete frame buffer
            final byte[] frame = new byte[2 + rest.length];
            frame[0] = address;
            frame[1] = function;
            System.arraycopy(rest, 0, frame, 2, rest.length);

            LOG.debug("Received Modbus frame: addr=0x{} func=0x{}",
                    Integer.toHexString(address & 0xFF),
                    Integer.toHexString(function & 0xFF));

            // Respond to any address that maps to one of our battery packs
            final int packIndex = address & 0xFF;
            final List<BatteryPack> packs = getEnergyStorage().getBatteryPacks();

            if (packIndex < packs.size()) {
                handlePackRequest(address, function, frame, packs.get(packIndex), port);
            }
            // else: ignore addresses beyond our battery packs
        }

        // All requests handled inline; createSendFrames will receive null and return empty
        return null;
    }


    /**
     * Responds to a single Modbus request for a specific battery pack.
     */
    private void handlePackRequest(final byte address, final byte function, final byte[] frame, final BatteryPack pack, final Port port) throws IOException {
        ByteBuffer response = null;

        if (function == (byte) 0x11) {
            LOG.debug("Responding to Report Slave ID (0x11) for address 0x{}", Integer.toHexString(address & 0xFF));
            response = createReportSlaveIdResponse(address);
        } else if (function == (byte) 0x04) {
            final int startReg = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
            final int count = ((frame[4] & 0xFF) << 8) | (frame[5] & 0xFF);
            LOG.debug("Responding to Read Input Registers (0x04): start=0x{} count={}",
                    Integer.toHexString(startReg), count);
            if (startReg == REG_START && count == REG_COUNT) {
                response = createBatteryDataResponse(address, pack);
            }
        }

        if (response != null) {
            port.sendFrame(response);
        }
    }


    @Override
    protected List<ByteBuffer> createSendFrames(final ByteBuffer requestFrame, final BatteryPack aggregatedPack) {
        // All responses are sent inline from readRequest(); nothing to do here.
        return new ArrayList<>();
    }


    @Override
    protected void sendFrame(final Port port, final ByteBuffer frame) throws IOException {
        port.sendFrame(frame);
    }


    /**
     * Builds a Modbus RTU Report Slave ID response.
     * Format: [addr] [0x11] [byteCount] [0xFF=running] [slaveId...] [CRC-lo] [CRC-hi]
     */
    private ByteBuffer createReportSlaveIdResponse(final byte address) {
        final byte[] slaveId = new byte[] { 'B', 'M', 'S' };
        final int dataLen = 1 + slaveId.length; // run indicator + slave ID
        final byte[] frame = new byte[3 + dataLen + 2]; // addr+func+count + data + CRC

        frame[0] = address;
        frame[1] = 0x11;
        frame[2] = (byte) dataLen;
        frame[3] = (byte) 0xFF; // run indicator: 0xFF = running
        System.arraycopy(slaveId, 0, frame, 4, slaveId.length);

        appendCRC(frame);
        return ByteBuffer.wrap(frame);
    }


    /**
     * Builds a Modbus RTU Read Input Registers response with 23 registers at 0x1000.
     *
     * Register map (Deye SUN-8K Pylontech mode — empirically confirmed):
     * 0x1000: Pack voltage (0.01V per bit)          — confirmed: matches displayed value
     * 0x1001: Pack current (0.1A, signed)
     * 0x1002: SOC (integer %)                         — 0–100; Deye clips values > 100 to 100%
     * 0x1003: Temperature (0.1°C per bit)            — confirmed: SOH=100 there showed as 10°C
     * 0x1004: SOH (integer %)                         — 0–100
     * 0x1005: Status flags (bit0=charge, bit1=discharge, bit2=force)  — confirmed: value shown as hex in fault col 1 (3="3|0|0")
     * 0x1006: Alarm flags 1                          — confirmed: cell mV here showed as fault code
     * 0x1007: Alarm flags 2
     * 0x1008: Max charge voltage (0.01V per bit)
     * 0x1009: Min discharge voltage (0.01V per bit)
     * 0x100A: Max charge current (0.1A per bit)
     * 0x100B: Max discharge current (0.1A per bit)
     * 0x100C: Warning flags 1
     * 0x100D: Warning flags 2
     * 0x100E: Fault flags 1
     * 0x100F: Fault flags 2
     * 0x1010: Max cell voltage (mV)
     * 0x1011: Min cell voltage (mV)
     * 0x1012: Battery unit count
     * 0x1013: Cells per unit
     * 0x1014: Max cell temperature (0.1°C)
     * 0x1015: Min cell temperature (0.1°C)
     * 0x1016: Cycle count
     */
    private ByteBuffer createBatteryDataResponse(final byte address, final BatteryPack pack) {
        final int byteCount = REG_COUNT * 2; // 23 registers × 2 bytes each = 46 bytes
        final byte[] frame = new byte[3 + byteCount + 2]; // addr+func+count + data + CRC

        frame[0] = address;
        frame[1] = 0x04;
        frame[2] = (byte) byteCount;

        // packSOC/packSOH stored internally as 0.1% units (e.g. 960 = 96.0%); Deye expects integer 0-100
        final int soc = pack.packSOC / 10;
        final int soh = pack.packSOH > 0 ? pack.packSOH / 10 : 100;

        final int tempAvg = (pack.tempMax + pack.tempMin) / 2;

        int status = 0;
        if (pack.chargeMOSState) status |= 0x01;
        if (pack.dischargeMOSState) status |= 0x02;
        if (pack.forceCharge) status |= 0x04;

        // maxPackVoltageLimit/minPackVoltageLimit in 0.1V; register expects 0.01V → ×10
        final int maxChargeV    = pack.maxPackVoltageLimit    > 0 ? pack.maxPackVoltageLimit    * 10 : 5760;
        final int minDischargeV = pack.minPackVoltageLimit    > 0 ? pack.minPackVoltageLimit    * 10 : 4000;
        final int maxChargeCurr = pack.maxPackChargeCurrent   > 0 ? pack.maxPackChargeCurrent        : 750;
        final int maxDischgCurr = pack.maxPackDischargeCurrent > 0 ? pack.maxPackDischargeCurrent    : 1500;

        int offset = 3;
        offset = putShort(frame, offset, pack.packVoltage * 10);  // 0x1000: voltage (0.01V) — CONFIRMED
        offset = putShort(frame, offset, pack.packCurrent);        // 0x1001: current (0.1A, signed)
        offset = putShort(frame, offset, soc);                     // 0x1002: SOC (0.1%) — e.g. 960 = 96.0%
        offset = putShort(frame, offset, tempAvg);                 // 0x1003: temperature (0.1°C) — CONFIRMED
        offset = putShort(frame, offset, soh);                     // 0x1004: SOH (0.1%)
        offset = putShort(frame, offset, status);                  // 0x1005: status flags — CONFIRMED drives fault col 1 (status=3 → "3|0|0")
        offset = putShort(frame, offset, 0);                       // 0x1006: alarm flags 1 — zero to clear fault display
        offset = putShort(frame, offset, 0);                       // 0x1007: alarm flags 2 — zero to clear fault display
        offset = putShort(frame, offset, maxChargeV);              // 0x1008: max charge voltage (0.01V)
        offset = putShort(frame, offset, minDischargeV);           // 0x1009: min discharge voltage (0.01V)
        offset = putShort(frame, offset, maxChargeCurr);           // 0x100A: max charge current (0.1A)
        offset = putShort(frame, offset, maxDischgCurr);           // 0x100B: max discharge current (0.1A)
        offset = putShort(frame, offset, 0);                       // 0x100C: warning flags 1
        offset = putShort(frame, offset, 0);                       // 0x100D: warning flags 2
        offset = putShort(frame, offset, 0);                       // 0x100E: fault flags 1
        offset = putShort(frame, offset, 0);                       // 0x100F: fault flags 2
        offset = putShort(frame, offset, pack.maxCellmV);          // 0x1010: max cell voltage (mV)
        offset = putShort(frame, offset, pack.minCellmV);          // 0x1011: min cell voltage (mV)
        offset = putShort(frame, offset, 1);                       // 0x1012: battery unit count
        offset = putShort(frame, offset, pack.numberOfCells);      // 0x1013: cells per unit
        offset = putShort(frame, offset, pack.tempMax);            // 0x1014: max cell temp (0.1°C)
        offset = putShort(frame, offset, pack.tempMin);            // 0x1015: min cell temp (0.1°C)
        putShort(frame, offset, pack.bmsCycles);                   // 0x1016: cycle count

        final StringBuilder hex = new StringBuilder();
        for (final byte b : frame) hex.append(String.format("%02X ", b));
        LOG.info("Deye BMS response addr=0x{}: SOC={}% voltage={}V current={}A temp={}°C | frame={}",
                Integer.toHexString(address & 0xFF),
                soc, pack.packVoltage / 10.0, pack.packCurrent / 10.0, tempAvg / 10.0, hex);

        appendCRC(frame);
        return ByteBuffer.wrap(frame);
    }


    private int putShort(final byte[] data, final int offset, final int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
        return offset + 2;
    }


    private void appendCRC(final byte[] frame) {
        final int crc = calculateCRC(frame, frame.length - 2);
        frame[frame.length - 2] = (byte) (crc & 0xFF);
        frame[frame.length - 1] = (byte) ((crc >> 8) & 0xFF);
    }


    private int calculateCRC(final byte[] buf, final int len) {
        int crc = 0xFFFF;
        for (int pos = 0; pos < len; pos++) {
            crc ^= buf[pos] & 0xFF;
            for (int i = 8; i != 0; i--) {
                if ((crc & 0x0001) != 0) {
                    crc >>= 1;
                    crc ^= 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc;
    }
}

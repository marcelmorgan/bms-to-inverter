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
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.airepublic.bmstoinverter.core.AlarmLevel;
import com.airepublic.bmstoinverter.core.Inverter;
import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.core.bms.data.Alarm;
import com.airepublic.bmstoinverter.core.bms.data.BatteryPack;
import com.airepublic.bmstoinverter.core.util.BitUtil;
import com.airepublic.bmstoinverter.core.util.ByteAsciiConverter;
import com.airepublic.bmstoinverter.protocol.rs485.JSerialCommPort;

/**
 * Modbus RTU slave processor for Deye inverters. The Deye BMS port sends two frame types:
 * (1) Binary Modbus RTU queries (function 0x11 / 0x04) to addresses 0x00-0x0E for per-pack data,
 * (2) ASCII Pylontech frames (SOI=0x7E, CID2=0x61/0x62/0x63) for system-level SOC display.
 * This processor responds to both protocols inline from readRequest().
 */
@ApplicationScoped
public class Pylon2InverterRS485Processor extends Inverter {
    private final static Logger LOG = LoggerFactory.getLogger(Pylon2InverterRS485Processor.class);

    private static final int REG_START = 0x1000;
    private static final int REG_COUNT = 23;

    /** Last aggregated pack saved from createSendFrames() for use in ASCII responses. */
    private volatile BatteryPack lastAggregatedPack;

    @Override
    protected ByteBuffer readRequest(final Port port) throws IOException {
        final JSerialCommPort serialPort = (JSerialCommPort) port;
        serialPort.ensureOpen();
        final long deadline = System.currentTimeMillis() + 3000;

        while (System.currentTimeMillis() < deadline) {
            final byte[] addrBuf = new byte[1];
            if (serialPort.readBytes(addrBuf, 400) == -1) {
                continue;
            }

            final byte firstByte = addrBuf[0];

            // ASCII Pylontech frame starts with ~ (0x7E)
            if (firstByte == (byte) 0x7E) {
                handleAsciiFrame(serialPort, port);
                continue;
            }

            final byte address = firstByte;

            // Deye only polls addresses 0x00-0x0E for Modbus
            if ((address & 0xFF) > 0x0E) {
                continue;
            }

            final byte[] funcBuf = new byte[1];
            if (serialPort.readBytes(funcBuf, 100) == -1) {
                continue;
            }

            final byte function = funcBuf[0];

            final byte[] rest;
            if (function == (byte) 0x11) {
                rest = new byte[2];
            } else if (function == (byte) 0x04) {
                rest = new byte[6];
            } else {
                continue;
            }

            if (serialPort.readBytes(rest, 100) == -1) {
                continue;
            }

            final byte[] frameData = new byte[2 + rest.length - 2];
            frameData[0] = address;
            frameData[1] = function;
            System.arraycopy(rest, 0, frameData, 2, rest.length - 2);
            final int expectedCRC = calculateCRC(frameData, frameData.length);
            final int actualCRC = (rest[rest.length - 2] & 0xFF) | ((rest[rest.length - 1] & 0xFF) << 8);

            if (expectedCRC != actualCRC) {
                LOG.debug("CRC mismatch addr=0x{} func=0x{}: expected=0x{} actual=0x{}",
                        Integer.toHexString(address & 0xFF), Integer.toHexString(function & 0xFF),
                        Integer.toHexString(expectedCRC), Integer.toHexString(actualCRC));
                continue;
            }

            final byte[] frame = new byte[2 + rest.length];
            frame[0] = address;
            frame[1] = function;
            System.arraycopy(rest, 0, frame, 2, rest.length);

            LOG.debug("Modbus frame: addr=0x{} func=0x{}",
                    Integer.toHexString(address & 0xFF), Integer.toHexString(function & 0xFF));

            final int packIndex = address & 0xFF;
            final List<BatteryPack> packs = getEnergyStorage().getBatteryPacks();
            if (packIndex < packs.size()) {
                handlePackRequest(address, function, frame, packs.get(packIndex), port);
            }
        }

        return null;
    }


    private void handlePackRequest(final byte address, final byte function, final byte[] frame, final BatteryPack pack, final Port port) throws IOException {
        ByteBuffer response = null;

        if (function == (byte) 0x11) {
            LOG.debug("Report Slave ID (0x11) addr=0x{}", Integer.toHexString(address & 0xFF));
            response = createReportSlaveIdResponse(address);
        } else if (function == (byte) 0x04) {
            final int startReg = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
            final int count = ((frame[4] & 0xFF) << 8) | (frame[5] & 0xFF);
            LOG.debug("Read Input Registers (0x04): start=0x{} count={}", Integer.toHexString(startReg), count);
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
        if (aggregatedPack != null) {
            lastAggregatedPack = aggregatedPack;
        }
        return new ArrayList<>();
    }


    @Override
    protected void sendFrame(final Port port, final ByteBuffer frame) throws IOException {
        port.sendFrame(frame);
    }


    /**
     * Handles an ASCII Pylontech frame (SOI 0x7E already consumed).
     * Reads until CR (0x0D), parses CID1/CID2, sends response.
     */
    private void handleAsciiFrame(final JSerialCommPort serialPort, final Port port) throws IOException {
        final byte[] buf = new byte[256];
        int len = 0;
        final long frameDeadline = System.currentTimeMillis() + 500;

        while (System.currentTimeMillis() < frameDeadline && len < buf.length) {
            final byte[] b = new byte[1];
            if (serialPort.readBytes(b, 100) == -1) {
                continue;
            }
            if (b[0] == (byte) 0x0D) {
                break;
            }
            buf[len++] = b[0];
        }

        // Minimum (no data): VER(2)+ADR(2)+CID1(2)+CID2(2)+LEN(4)+CKSUM(4) = 16
        if (len < 16) {
            LOG.debug("ASCII frame too short ({} bytes)", len);
            return;
        }

        // buf[0-1]=VER, buf[2-3]=ADR, buf[4-5]=CID1, buf[6-7]=CID2, buf[8-11]=LEN, ...
        final byte adr  = ByteAsciiConverter.convertAsciiBytesToByte(buf[2], buf[3]);
        final byte cid1 = ByteAsciiConverter.convertAsciiBytesToByte(buf[4], buf[5]);
        final byte cid2 = ByteAsciiConverter.convertAsciiBytesToByte(buf[6], buf[7]);

        LOG.debug("ASCII frame: adr=0x{} CID1=0x{} CID2=0x{}",
                Integer.toHexString(adr & 0xFF), Integer.toHexString(cid1 & 0xFF), Integer.toHexString(cid2 & 0xFF));

        if ((cid1 & 0xFF) != 0x46) {
            return;
        }

        final BatteryPack pack = lastAggregatedPack;
        if (pack == null) {
            LOG.warn("ASCII frame received but no aggregated pack data available yet");
            return;
        }

        byte[] responseData;
        switch (cid2 & 0xFF) {
            case 0x61:
                responseData = createAsciiInfo(pack);
            break;
            case 0x62:
                responseData = createAsciiAlarms(pack);
            break;
            case 0x63:
                responseData = createAsciiLimits(pack);
            break;
            default:
                LOG.debug("Unsupported ASCII CID2=0x{}", Integer.toHexString(cid2 & 0xFF));
                return;
        }

        final ByteBuffer response = buildAsciiFrame(adr, (byte) 0x46, (byte) 0x00, responseData);
        port.sendFrame(response);
        LOG.info("ASCII response: adr=0x{} CID2=0x{} SOC={}%",
                Integer.toHexString(adr & 0xFF), Integer.toHexString(cid2 & 0xFF), pack.packSOC / 10);
    }


    /**
     * CID2=0x61 battery information (98 ASCII chars, 26 fields).
     * SOC is field 3 encoded as 2 ASCII hex chars (integer 0-100).
     */
    private byte[] createAsciiInfo(final BatteryPack pack) {
        final ByteBuffer buf = ByteBuffer.allocate(256);

        buf.put(ByteAsciiConverter.convertCharToAsciiBytes((char) (pack.packVoltage * 100)));           // voltage mV
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) (pack.packCurrent * 10)));          // current cA
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) (pack.packSOC / 10)));                // SOC integer %
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) pack.bmsCycles));                   // avg cycles
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) 10000));                            // max cycles
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) (pack.packSOH > 0 ? pack.packSOH / 10 : 100))); // avg SOH %
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) (pack.packSOH > 0 ? pack.packSOH / 10 : 100))); // min SOH %

        int maxPack = 0, minPack = 0;
        for (int i = 0; i < getEnergyStorage().getBatteryPacks().size(); i++) {
            final BatteryPack p = getEnergyStorage().getBatteryPack(i);
            if (p.maxCellmV == pack.maxCellmV) maxPack = i;
            if (p.minCellmV == pack.minCellmV) minPack = i;
        }

        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) pack.maxCellmV));                   // max cell V (mV)
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) maxPack));                            // pack# with max cell
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) pack.maxCellVNum));                   // cell# with max V
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) pack.minCellmV));                   // min cell V (mV)
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) minPack));                            // pack# with min cell
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) pack.minCellVNum));                   // cell# with min V

        final int avgTempDk = pack.tempAverage + 2731;
        final int maxTempDk = pack.tempMax + 2731;
        final int minTempDk = pack.tempMin + 2731;

        int maxTempPack = 0, minTempPack = 0;
        for (int i = 0; i < getEnergyStorage().getBatteryPacks().size(); i++) {
            final BatteryPack p = getEnergyStorage().getBatteryPack(i);
            if (p.tempMax == pack.tempMax) maxTempPack = i;
            if (p.tempMin == pack.tempMin) minTempPack = i;
        }

        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) avgTempDk));                        // avg cell temp (0.1K)
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) maxTempDk));                        // max cell temp
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) maxTempPack));                        // pack# with max temp
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) pack.tempMaxCellNum));                // cell# with max temp
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) minTempDk));                        // min cell temp
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) minTempPack));                        // pack# with min temp
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes((byte) pack.tempMinCellNum));                // cell# with min temp

        // MOSFET temperatures (approximate with cell average)
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) avgTempDk));                        // MOSFET avg
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) avgTempDk));                        // MOSFET max
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) 0));                                // MOSFET max pack#
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) avgTempDk));                        // MOSFET min
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) 0));                                // MOSFET min pack#

        // BMS temperatures (approximate with cell average)
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) avgTempDk));                        // BMS avg
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) avgTempDk));                        // BMS max
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) 0));                                // BMS max pack#
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) avgTempDk));                        // BMS min
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) 0));                                // BMS min pack#

        final int size = buf.position();
        final byte[] data = new byte[size];
        buf.rewind();
        buf.get(data);
        return data;
    }


    /**
     * CID2=0x62 alarm data (8 ASCII chars = 4 alarm flag bytes).
     */
    private byte[] createAsciiAlarms(final BatteryPack pack) {
        final byte[] result = new byte[8];
        byte value;
        byte[] bytes;

        value = 0;
        value = BitUtil.setBit(value, 7, pack.getAlarmLevel(Alarm.PACK_VOLTAGE_HIGH) == AlarmLevel.WARNING);
        value = BitUtil.setBit(value, 6, pack.getAlarmLevel(Alarm.PACK_VOLTAGE_LOW) == AlarmLevel.WARNING);
        value = BitUtil.setBit(value, 5, pack.getAlarmLevel(Alarm.CELL_VOLTAGE_HIGH) == AlarmLevel.WARNING);
        value = BitUtil.setBit(value, 4, pack.getAlarmLevel(Alarm.CELL_VOLTAGE_LOW) == AlarmLevel.WARNING);
        value = BitUtil.setBit(value, 3, pack.getAlarmLevel(Alarm.CELL_TEMPERATURE_HIGH) == AlarmLevel.WARNING);
        value = BitUtil.setBit(value, 2, pack.getAlarmLevel(Alarm.CELL_TEMPERATURE_LOW) == AlarmLevel.WARNING);
        bytes = ByteAsciiConverter.convertByteToAsciiBytes(value);
        result[0] = bytes[0]; result[1] = bytes[1];

        value = 0;
        value = BitUtil.setBit(value, 6, pack.getAlarmLevel(Alarm.CHARGE_CURRENT_HIGH) == AlarmLevel.WARNING);
        value = BitUtil.setBit(value, 5, pack.getAlarmLevel(Alarm.DISCHARGE_CURRENT_HIGH) == AlarmLevel.WARNING);
        bytes = ByteAsciiConverter.convertByteToAsciiBytes(value);
        result[2] = bytes[0]; result[3] = bytes[1];

        value = 0;
        value = BitUtil.setBit(value, 7, pack.getAlarmLevel(Alarm.PACK_VOLTAGE_HIGH) == AlarmLevel.ALARM);
        value = BitUtil.setBit(value, 6, pack.getAlarmLevel(Alarm.PACK_VOLTAGE_LOW) == AlarmLevel.ALARM);
        value = BitUtil.setBit(value, 5, pack.getAlarmLevel(Alarm.CELL_VOLTAGE_HIGH) == AlarmLevel.ALARM);
        value = BitUtil.setBit(value, 4, pack.getAlarmLevel(Alarm.CELL_VOLTAGE_LOW) == AlarmLevel.ALARM);
        value = BitUtil.setBit(value, 3, pack.getAlarmLevel(Alarm.CELL_TEMPERATURE_HIGH) == AlarmLevel.ALARM);
        value = BitUtil.setBit(value, 2, pack.getAlarmLevel(Alarm.CELL_TEMPERATURE_LOW) == AlarmLevel.ALARM);
        bytes = ByteAsciiConverter.convertByteToAsciiBytes(value);
        result[4] = bytes[0]; result[5] = bytes[1];

        value = 0;
        value = BitUtil.setBit(value, 6, pack.getAlarmLevel(Alarm.CHARGE_CURRENT_HIGH) == AlarmLevel.ALARM);
        value = BitUtil.setBit(value, 5, pack.getAlarmLevel(Alarm.DISCHARGE_CURRENT_HIGH) == AlarmLevel.ALARM);
        bytes = ByteAsciiConverter.convertByteToAsciiBytes(value);
        result[6] = bytes[0]; result[7] = bytes[1];

        return result;
    }


    /**
     * CID2=0x63 charge/discharge limits.
     */
    private byte[] createAsciiLimits(final BatteryPack pack) {
        final ByteBuffer buf = ByteBuffer.allocate(64);

        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) (pack.maxPackVoltageLimit * 100)));    // max charge V (0.01V)
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) (pack.minPackVoltageLimit * 100)));    // min discharge V (0.01V)
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) (pack.maxPackChargeCurrent * 10)));    // max charge I (0.01A)
        buf.put(ByteAsciiConverter.convertShortToAsciiBytes((short) (pack.maxPackDischargeCurrent * 10))); // max discharge I (0.01A)

        byte flags = 0;
        flags = BitUtil.setBit(flags, 7, pack.chargeMOSState);
        flags = BitUtil.setBit(flags, 6, pack.dischargeMOSState);
        flags = BitUtil.setBit(flags, 5, pack.forceCharge);
        buf.put(ByteAsciiConverter.convertByteToAsciiBytes(flags));

        final int size = buf.position();
        final byte[] data = new byte[size];
        buf.rewind();
        buf.get(data);
        return data;
    }


    /**
     * Builds an ASCII Pylontech response frame.
     * Layout: SOI(1) VER(2) ADR(2) CID1(2) CID2(2) LENGTH(4) DATA(n) CHECKSUM(4) EOI(1)
     */
    private ByteBuffer buildAsciiFrame(final byte address, final byte cid1, final byte cid2, final byte[] data) {
        final ByteBuffer frame = ByteBuffer.allocate(18 + data.length).order(ByteOrder.BIG_ENDIAN);
        frame.put((byte) 0x7E);
        frame.put((byte) 0x32); // '2'
        frame.put((byte) 0x30); // '0'
        frame.put(ByteAsciiConverter.convertByteToAsciiBytes(address));
        frame.put(ByteAsciiConverter.convertByteToAsciiBytes(cid1));
        frame.put(ByteAsciiConverter.convertByteToAsciiBytes(cid2));
        frame.put(asciiLengthField(data.length));
        frame.put(data);
        frame.put(asciiFrameChecksum(frame));
        frame.put((byte) 0x0D);
        frame.flip();
        return frame;
    }


    private byte[] asciiLengthField(final int length) {
        final int lenId = length & 0x0FFF;
        final int high = (lenId >> 8) & 0xFF;
        final int low = lenId & 0xFF;
        final int lchk = (~((high + low) & 0x0F) + 1) & 0x0F;
        final int lengthField = (lchk << 12 | lenId) & 0xFFFF;
        final byte hiB = (byte) ((lengthField >> 8) & 0xFF);
        final byte loB = (byte) (lengthField & 0xFF);
        final byte[] hiA = ByteAsciiConverter.convertByteToAsciiBytes(hiB);
        final byte[] loA = ByteAsciiConverter.convertByteToAsciiBytes(loB);
        return new byte[] { hiA[0], hiA[1], loA[0], loA[1] };
    }


    private byte[] asciiFrameChecksum(final ByteBuffer frame) {
        // Sum bytes 1..capacity-1 (skip SOI; unwritten trailing bytes are zero)
        int sum = 0;
        for (int i = 1; i < frame.capacity(); i++) {
            sum += frame.get(i) & 0xFF;
        }
        final int ck = (~sum + 1) & 0xFFFF;
        final byte hi = (byte) ((ck >> 8) & 0xFF);
        final byte lo = (byte) (ck & 0xFF);
        final byte[] hiA = ByteAsciiConverter.convertByteToAsciiBytes(hi);
        final byte[] loA = ByteAsciiConverter.convertByteToAsciiBytes(lo);
        return new byte[] { hiA[0], hiA[1], loA[0], loA[1] };
    }


    /**
     * Builds a Modbus RTU Report Slave ID response.
     */
    private ByteBuffer createReportSlaveIdResponse(final byte address) {
        final byte[] slaveId = new byte[] { 'B', 'M', 'S' };
        final int dataLen = 1 + slaveId.length;
        final byte[] frame = new byte[3 + dataLen + 2];

        frame[0] = address;
        frame[1] = 0x11;
        frame[2] = (byte) dataLen;
        frame[3] = (byte) 0xFF;
        System.arraycopy(slaveId, 0, frame, 4, slaveId.length);

        appendCRC(frame);
        return ByteBuffer.wrap(frame);
    }


    /**
     * Builds a Modbus RTU Read Input Registers response with 23 registers at 0x1000.
     *
     * Register map (Deye SUN-8K Pylontech mode — empirically confirmed):
     * 0x1000: Pack voltage (0.01V)    confirmed matches display
     * 0x1001: Pack current (0.1A signed)
     * 0x1002: SOC (integer %)         0-100
     * 0x1003: Temperature (0.1°C)     confirmed
     * 0x1004: SOH (integer %)
     * 0x1005: Status flags            confirmed drives fault col 1
     * 0x1006: Alarm flags 1
     * 0x1007: Alarm flags 2
     * 0x1008: Max charge voltage (0.01V)
     * 0x1009: Min discharge voltage (0.01V)
     * 0x100A: Max charge current (0.1A)
     * 0x100B: Max discharge current (0.1A)
     * 0x100C-0x100F: Warning/fault flags
     * 0x1010: Max cell voltage (mV)
     * 0x1011: Min cell voltage (mV)
     * 0x1012: Battery unit count
     * 0x1013: Cells per unit
     * 0x1014: Max cell temp (0.1°C)
     * 0x1015: Min cell temp (0.1°C)
     * 0x1016: Cycle count
     */
    private ByteBuffer createBatteryDataResponse(final byte address, final BatteryPack pack) {
        final int byteCount = REG_COUNT * 2;
        final byte[] frame = new byte[3 + byteCount + 2];

        frame[0] = address;
        frame[1] = 0x04;
        frame[2] = (byte) byteCount;

        // packSOC/packSOH in 0.1% units internally; Deye Modbus register expects integer 0-100
        final int soc = pack.packSOC / 10;
        final int soh = pack.packSOH > 0 ? pack.packSOH / 10 : 100;
        final int tempAvg = (pack.tempMax + pack.tempMin) / 2;

        int status = 0;
        if (pack.chargeMOSState) status |= 0x01;
        if (pack.dischargeMOSState) status |= 0x02;
        if (pack.forceCharge) status |= 0x04;

        // maxPackVoltageLimit in 0.1V; register 0x1008 expects 0.01V → ×10
        final int maxChargeV    = pack.maxPackVoltageLimit    > 0 ? pack.maxPackVoltageLimit    * 10 : 5760;
        final int minDischargeV = pack.minPackVoltageLimit    > 0 ? pack.minPackVoltageLimit    * 10 : 4000;
        final int maxChargeCurr = pack.maxPackChargeCurrent   > 0 ? pack.maxPackChargeCurrent        : 750;
        final int maxDischgCurr = pack.maxPackDischargeCurrent > 0 ? pack.maxPackDischargeCurrent    : 1500;

        int offset = 3;
        offset = putShort(frame, offset, pack.packVoltage * 10);
        offset = putShort(frame, offset, pack.packCurrent);
        offset = putShort(frame, offset, soc);
        offset = putShort(frame, offset, tempAvg);
        offset = putShort(frame, offset, soh);
        offset = putShort(frame, offset, status);
        offset = putShort(frame, offset, 0);
        offset = putShort(frame, offset, 0);
        offset = putShort(frame, offset, maxChargeV);
        offset = putShort(frame, offset, minDischargeV);
        offset = putShort(frame, offset, maxChargeCurr);
        offset = putShort(frame, offset, maxDischgCurr);
        offset = putShort(frame, offset, 0);
        offset = putShort(frame, offset, 0);
        offset = putShort(frame, offset, 0);
        offset = putShort(frame, offset, 0);
        offset = putShort(frame, offset, pack.maxCellmV);
        offset = putShort(frame, offset, pack.minCellmV);
        offset = putShort(frame, offset, 1);
        offset = putShort(frame, offset, pack.numberOfCells);
        offset = putShort(frame, offset, pack.tempMax);
        offset = putShort(frame, offset, pack.tempMin);
        putShort(frame, offset, pack.bmsCycles);

        final StringBuilder hex = new StringBuilder();
        for (final byte b : frame) hex.append(String.format("%02X ", b));
        LOG.info("Modbus response addr=0x{}: SOC={}% V={}V I={}A T={}°C | {}",
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

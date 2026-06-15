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
package com.airepublic.bmstoinverter.bms.huawei.esm48150.modbus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.airepublic.bmstoinverter.core.BMS;
import com.airepublic.bmstoinverter.core.Port;
import com.airepublic.bmstoinverter.core.bms.data.BatteryPack;
import com.airepublic.bmstoinverter.protocol.modbus.ModbusUtil;
import com.airepublic.bmstoinverter.protocol.modbus.ModbusUtil.RegisterCode;

/**
 * The class to handle Modbus messages from a Huawei ESM-48150B1 {@link BMS}.
 *
 * All data is fetched in a single FC03 request covering PDU addresses 0-44 (mbpoll regs 1-45)
 * to avoid bus contention with other devices sharing the RS485 line.
 *
 * Confirmed register map (mbpoll 1-based / PDU 0-based):
 *   1/0  : pack voltage    (raw ÷ 100 → 0.1V; unit is 0.01V, e.g. 4983 = 49.83V)
 *   2/1  : rated voltage   (informational, same unit)
 *   3/2  : pack current    (signed 0.1A; positive=charge, negative=discharge)
 *   4/3  : SOC             (% × 10 → 0.1%;   e.g. 86 → 860)
 *   5/4  : max charge limit (constant 100; SOH not exposed by this BMS)
 *   6/5  : temp max        (°C × 10 → 0.1°C)
 *   7/6  : temp min        (°C × 10 → 0.1°C)
 *   8-18/7-17 : zeros / status (unused)
 *  19-29/18-28: cell temperatures  (°C × 10 → 0.1°C; -999 = unavailable)
 *  30-34/29-33: unavailable sensor markers (-999)
 *  35-45/34-44: cell voltages (mV direct; 0xFFFF = no cell)
 */
public class HuaweiEsm48150BmsModbusProcessor extends BMS {
    private final static Logger LOG = LoggerFactory.getLogger(HuaweiEsm48150BmsModbusProcessor.class);

    @Override
    protected void collectData(final Port port) throws IOException {
        try {
            sendMessage(port, RegisterCode.READ_HOLDING_REGISTERS, 0, 45, getBmsId(), this::readAllData);
        } catch (final IOException e) {
            LOG.error("Error reading from ESM-48150B1 modbus!", e);
            throw e;
        }
    }


    protected void sendMessage(final Port port, final RegisterCode functionCode, final int startAddress, final int numRegisters, final int unitId, final Consumer<ByteBuffer> handler) throws IOException {
        port.sendFrame(ModbusUtil.createRequestBuffer(functionCode, startAddress, numRegisters, unitId));
        handler.accept(port.receiveFrame());
    }


    private void readAllData(final ByteBuffer frame) {
        frame.getInt(); // functionCode
        frame.getInt(); // numRegisters
        final int unitId = frame.getInt();
        final BatteryPack pack = getBatteryPack(unitId);

        // Each register is stored as a 4-byte int by ModbusUtil.toBuffer(); cast to short to get
        // the signed 16-bit value.

        // regs 1-7 (PDU 0-6): pack status
        pack.packVoltage = (short) frame.getInt() / 10;   // reg 1: raw in 0.01V × (0.01/0.1) → 0.1V
        frame.getInt();                                     // reg 2: skip (secondary voltage reading)
        pack.packCurrent = (short) frame.getInt();          // reg 3: signed 0.1A
        pack.packSOC = (short) frame.getInt() * 10;         // reg 4: SOC % → 0.1%
        frame.getInt();                                     // reg 5: constant 100 (max charge limit), not SOH
        pack.tempMax = (short) frame.getInt() * 10;         // reg 6: °C → 0.1°C
        pack.tempMin = (short) frame.getInt() * 10;         // reg 7: °C → 0.1°C
        pack.tempAverage = (pack.tempMax + pack.tempMin) / 2;

        // regs 8-18 (PDU 7-17): skip unused
        for (int i = 0; i < 11; i++) {
            frame.getInt();
        }

        // regs 19-29 (PDU 18-28): cell temperatures
        pack.numOfTempSensors = 0;
        for (int i = 0; i < 11; i++) {
            final short raw = (short) frame.getInt();
            if (raw != -999 && raw != -1) {
                pack.cellTemperature[i] = raw * 10; // °C → 0.1°C
                pack.numOfTempSensors++;
            }
        }

        // regs 30-34 (PDU 29-33): unavailable sensor markers (-999), skip
        for (int i = 0; i < 5; i++) {
            frame.getInt();
        }

        // regs 35-45 (PDU 34-44): cell voltages in mV
        pack.numberOfCells = 0;
        pack.minCellmV = Integer.MAX_VALUE;
        pack.maxCellmV = Integer.MIN_VALUE;
        for (int i = 0; i < 11; i++) {
            final short raw = (short) frame.getInt();
            if (raw != -1) { // 0xFFFF = no cell connected
                pack.cellVmV[i] = raw; // mV direct
                pack.numberOfCells++;
                if (raw < pack.minCellmV) {
                    pack.minCellmV = raw;
                    pack.minCellVNum = i;
                }
                if (raw > pack.maxCellmV) {
                    pack.maxCellmV = raw;
                    pack.maxCellVNum = i;
                }
            }
        }

        if (pack.numberOfCells > 0) {
            pack.cellDiffmV = pack.maxCellmV - pack.minCellmV;
        }
    }

}

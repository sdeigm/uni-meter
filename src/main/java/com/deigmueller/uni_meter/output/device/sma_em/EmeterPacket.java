package com.deigmueller.uni_meter.output.device.sma_em;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EmeterPacket {
  public static final long SMA_VERSION = 0x90000000L;

  private static final int INITIAL_PAYLOAD_LENGTH = 12;
  private static final int METER_PACKET_SIZE = 1000;

  private final byte[] meterPacket = new byte[METER_PACKET_SIZE];
  private int headerLength;
  private int pDataSize;
  private int pMeterTime;
  private int pPacketPos;
  private int length;

  public EmeterPacket(long serialNumber, int susyId) {
    initEmeterPacket(serialNumber, susyId);
    begin(0);
    end();
  }

  public void init(long serialNumber, int susyId) {
    initEmeterPacket(serialNumber, susyId);
  }

  public void begin(long timeStampMs) {
    pPacketPos = headerLength;
    storeU32BE(pMeterTime, timeStampMs);
    length = INITIAL_PAYLOAD_LENGTH;
  }

  public void addMeasurementValue(long id, long value) {
    pPacketPos = storeU32BE(pPacketPos, id);
    pPacketPos = storeU32BE(pPacketPos, value);
    length += 8;
  }

  public void addCounterValue(long id, long value) {
    pPacketPos = storeU32BE(pPacketPos, id);
    pPacketPos = storeU64BE(pPacketPos, value);
    length += 12;
  }

  public int end() {
    pPacketPos = storeU32BE(pPacketPos, SMA_VERSION);
    pPacketPos = storeU32BE(pPacketPos, 0x01020452L);
    length += 8;

    storeU16BE(pDataSize, length);
    storeU32BE(pPacketPos, 0);
    length += 4;

    length = headerLength + length - INITIAL_PAYLOAD_LENGTH;
    return length;
  }

  public byte@NotNull[] getData() {
    return meterPacket;
  }

  public int getLength() {
    return length;
  }

  private int storeU16BE(int pPos, long value) {
    meterPacket[pPos] = (byte) ((value >> 8) & 0xFF);
    meterPacket[pPos + 1] = (byte) (value & 0xFF);
    return pPos + 2;
  }

  private int storeU32BE(int pPos, long value) {
    pPos = storeU16BE(pPos, (value >> 16) & 0xFFFF);
    return storeU16BE(pPos, value & 0xFFFF);
  }

  private int storeU64BE(int pPos, long value) {
    pPos = storeU32BE(pPos, (value >> 32) & 0xFFFFFFFFL);
    return storeU32BE(pPos, value & 0xFFFFFFFFL);
  }

  private @Nullable Integer offsetOf(byte[] data, int identifier, int size) {
    for (int i = 0; i < size; i++) {
      if ((data[i] & 0xFF) == identifier) {
        return i;
      }
    }
    return null;
  }

  private void initEmeterPacket(long serialNumber, int susyId) {
    int wlen = 0xFA;
    int dsrc = 0xFB;
    int dtim = 0xFC;

    byte[] header = new byte[] {
          'S', 'M', 'A', 0,
          0x00, 0x04, 0x02, (byte) 0xA0, 0x00, 0x00, 0x00, 0x01,
          (byte) wlen, (byte) wlen, 0x00, 0x10, 0x60, 0x69,
          (byte) ((susyId >> 8) & 0xFF), (byte) (susyId & 0xFF),
          (byte) dsrc, (byte) dsrc, (byte) dsrc, (byte) dsrc,
          (byte) dtim, (byte) dtim, (byte) dtim, (byte) dtim
    };

    headerLength = header.length;
    System.arraycopy(header, 0, meterPacket, 0, headerLength);

    Integer dataSizeOffset = offsetOf(meterPacket, wlen, headerLength);
    Integer meterTimeOffset = offsetOf(meterPacket, dtim, headerLength);
    Integer serialOffset = offsetOf(meterPacket, dsrc, headerLength);
    if (dataSizeOffset == null || meterTimeOffset == null || serialOffset == null) {
      throw new IllegalStateException("failed to locate SMA header offsets");
    }
    pDataSize = dataSizeOffset;
    pMeterTime = meterTimeOffset;
    storeU32BE(serialOffset, serialNumber & 0xFFFFFFFFL);
  }
}

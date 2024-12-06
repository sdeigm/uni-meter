package com.deigmueller.uni_meter.input.device.sma.energy_meter;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

@Getter
public class ProtocolParser {
  public static @NotNull Telegram parse(byte@NotNull[] data, int dataLength) {
    checkValidMagicConstant(data);

    checkTag42(data);
    
    //long defaultChannel = readUInt32(data, 8);
    
    int protocolId = readUInt16(data, 16);
    
    int susyId = readUInt16(data, 18);
    
    long serialNumber = readUInt32(data, 20);
    
    long measuringTime = readUInt32(data, 24);


    Map<Long,BigInteger> values = new HashMap<>();
    
    int position = 28;
    while (position < dataLength) {
      long identifier = readUInt32(data, position);
      
      ObisChannel obisChannel = ObisChannel.get(identifier);
      if (obisChannel == null) {
        throw new IllegalArgumentException("unknown obis channel: " + identifier);
      }
      
      BigInteger value = BigInteger.ZERO;
      if (obisChannel.getDataLength() > 0) {
        value = readUnsignedBigInteger(data, position + 4, obisChannel.getDataLength());
      }
      
      values.put(identifier, value);
      
      position += 4 + obisChannel.getDataLength();
    }
    
    return new Telegram(susyId, serialNumber, 0, values);
  }
  
  private static void checkValidMagicConstant(byte[] data) {
    if (data[0] != 'S' || data[1] != 'M' || data[2] != 'A' || data[3] != 0) {
      throw new IllegalArgumentException("invalid magic packet constant");
    }
  }
  
  private static int checkProtocolId(byte[] data) {
    if (data[16] != 60 || data[17] != 69) {
      throw new IllegalArgumentException("invalid protocol id");
    }
    
    return readUInt16(data, 16);
  }
  
  private static void checkTag42(byte[] data) {
    if (data[5] != 4 || data[6] != 2) {
      throw new IllegalArgumentException("invalid tag42");
    }
  }

  private static int readUInt16(byte[] data, int offset) {
    return ((data[offset] & 0xFF) << 8) |
          (data[offset + 1] & 0xFF);
  }

  private static long readUInt32(byte[] data, int offset) {
    return (((long) (data[offset] & 0xFF)) << 24) |
           ((data[offset + 1] & 0xFF) << 16) |
           ((data[offset + 2] & 0xFF) << 8) |
           (data[offset + 3] & 0xFF);
  }
  
  private static BigInteger readUnsignedBigInteger(byte[] data, int offset, int length) {
    byte[] bytes = new byte[length];
    System.arraycopy(data, offset, bytes, 0, length);
    return new BigInteger(1, bytes);
  }
}

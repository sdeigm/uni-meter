package com.deigmueller.uni_meter.common.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class NetUtils {
  public static @NotNull String detectPrimaryIpAddress() {
    try(final DatagramSocket socket = new DatagramSocket()){
      socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
      return socket.getLocalAddress().getHostAddress();
    } catch (Exception e) {
      return "127.0.0.1";
    }  
  }
  
  public static @Nullable String detectPrimaryMacAddress() {
    Set<String> macAddresses = new TreeSet<>();
    
    try {
      Iterator<NetworkInterface> iterator = NetworkInterface.getNetworkInterfaces().asIterator();
      while (iterator.hasNext()) {
        try {
          NetworkInterface networkInterface = iterator.next();
          if (networkInterface.isUp() && !networkInterface.isLoopback() && !networkInterface.isVirtual()) {
            byte[] macAddress = networkInterface.getHardwareAddress();
            if (macAddress != null) {
              StringBuilder macAddressString = new StringBuilder();
              for (byte address : macAddress) {
                macAddressString.append(String.format("%02X", address));
              }
              macAddresses.add(macAddressString.toString());
            }
          }
        } catch (Exception e) {
          // ignore
        }
      }
    } catch (Exception e) {
      // ignore
    }
    
    List<String> list = macAddresses.stream().toList();
    
    return list.isEmpty() ? null : list.get(list.size() - 1);
  }
}

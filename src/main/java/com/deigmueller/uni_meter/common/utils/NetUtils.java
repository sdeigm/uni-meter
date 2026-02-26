package com.deigmueller.uni_meter.common.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.DatagramSocket;
import java.net.Inet4Address;
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

  /**
   * Detects the IP address associated with the specified network interface.
   * @param interfaceName the name of the network interface (e.g., "eth0", "wlan0")
   * @return the IP address as a string, or null if the interface is not found, not up, or has no valid IPv4 address
   */
  public static @Nullable String detectIpAddressFromInterface(@NotNull String interfaceName) {
    try {
      NetworkInterface networkInterface = NetworkInterface.getByName(interfaceName);
      if (networkInterface == null || !networkInterface.isUp()) {
        return null;
      }

      Iterator<InetAddress> iterator = networkInterface.getInetAddresses().asIterator();
      while (iterator.hasNext()) {
        InetAddress address = iterator.next();
        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
          return address.getHostAddress();
        }
      }
    } catch (Exception e) {
      // ignore
    }

    return null;
  }

  /**
   * Lists the names of all network interfaces available on the system.
   * @return a list of network interface names, sorted alphabetically
   */
  public static @NotNull List<String> listNetworkInterfaceNames() {
    Set<String> names = new TreeSet<>();

    try {
      Iterator<NetworkInterface> iterator = NetworkInterface.getNetworkInterfaces().asIterator();
      while (iterator.hasNext()) {
        try {
          names.add(iterator.next().getName());
        } catch (Exception e) {
          // ignore
        }
      }
    } catch (Exception e) {
      // ignore
    }

    return names.stream().toList();
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

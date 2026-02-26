package com.deigmueller.uni_meter.common.utils;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.stream.Streams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
  * Detects the primary MAC address of the system by iterating through all network interfaces and returning the last valid MAC address found.
  * Loopback and virtual interfaces are ignored.
  * @return the primary MAC address as a string, or null if no valid MAC address is found
  */
 public static @Nullable String detectPrimaryMacAddress() {
   try {
     var foundAddresses = Streams.of(NetworkInterface.getNetworkInterfaces()) //
     .filter(n -> !isLoopbackOrVirtual(n)) // filter out loopback and virtual interfaces
     .map(NetUtils::hardwareAddressToString) // convert to string representation of the MAC address
     .filter(Objects::nonNull) // filter out interfaces without a hardware address
     .toList();
     return foundAddresses.isEmpty() ? null : foundAddresses.get(foundAddresses.size() - 1);
   } catch (SocketException e) {
     // We dont't care
     return null;
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
      return Streams.of(networkInterface.getInetAddresses()) //
          .filter(Inet4Address.class::isInstance) //
          .filter(a -> !a.isLoopbackAddress()) //
          .map(InetAddress::getHostAddress) //
          .findFirst().orElse(null);
    } catch (Exception e) {
      // We don't care
    }
    return null;
  }

  /**
   * Lists the names of all network interfaces available on the system.
   * @return a list of network interface names, sorted alphabetically
   */
  public static @NotNull List<String> listNetworkInterfaceNames() {
    try {
      return Streams.of(NetworkInterface.getNetworkInterfaces()).map(NetworkInterface::getName).sorted().toList();
    } catch (SocketException e) {
      // We don't care about the exception, we just want to return an empty list in this case
    }
    return Collections.emptyList();
  }

  /**
   * Checks if the given network interface is a loopback or virtual interface.
   * @param networkInterface the network interface to check
   * @return true if the interface is a loopback or virtual interface, false otherwise
   */
  private static boolean isLoopbackOrVirtual(NetworkInterface networkInterface) {
    try {
      return networkInterface.isLoopback() || networkInterface.isVirtual();
    } catch (Exception e) {
      // We don't care
      return false;
    }
  }
  
  /**
   * Converts the hardware address (MAC address) of a network interface to a string representation.
   * @param networkInterface the network interface whose hardware address is to be converted
   * @return the string representation of the hardware address, or null if the hardware address cannot be retrieved
   */
  public static String hardwareAddressToString(NetworkInterface networkInterface ) {
    try {
      byte[] macAddress = networkInterface.getHardwareAddress();
      StringBuilder macAddressString = new StringBuilder();
      for (byte address : macAddress) {
        macAddressString.append(String.format("%02X", address));
      }
      return macAddressString.toString();
    } catch(SocketException e) {
      return null;
    }
  }

}

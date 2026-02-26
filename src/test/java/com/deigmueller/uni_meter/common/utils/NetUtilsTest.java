package com.deigmueller.uni_meter.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class NetUtilsTest {

  @Test
  @DisplayName("detectIpAddressFromInterface returns null for unknown interface")
  void detectIpAddressFromInterfaceReturnsNullForUnknownInterface() {
    String ipAddress = NetUtils.detectIpAddressFromInterface("definitely-not-a-real-interface-1234");

    assertThat(ipAddress, is((String) null));
  }

  @Test
  @DisplayName("listNetworkInterfaceNames returns a sorted list")
  void listNetworkInterfaceNamesReturnsSortedList() {
    List<String> interfaceNames = NetUtils.listNetworkInterfaceNames();

    assertThat(interfaceNames, is(notNullValue()));

    List<String> sortedInterfaceNames = new ArrayList<>(interfaceNames);
    Collections.sort(sortedInterfaceNames);

    assertThat(interfaceNames, is(sortedInterfaceNames));
  }
}

#  Copyright (C) 2024 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

import unittest
from unittest.mock import MagicMock, patch
from mobly.controllers.android_device_lib.adb import AdbError
from net_tests_utils.host.python.apf_utils import (
    PatternNotFoundException,
    UnsupportedOperationException,
    get_apf_counter,
    get_apf_counters_from_dumpsys,
    get_hardware_address,
    send_broadcast_empty_ethercat_packet,
    send_raw_packet_downstream,
)
from net_tests_utils.host.python.assert_utils import UnexpectedBehaviorError


class TestApfUtils(unittest.TestCase):

  def setUp(self):
    self.mock_ad = MagicMock()  # Mock Android device object

  @patch("net_tests_utils.host.python.adb_utils.get_dumpsys_for_service")
  def test_get_apf_counters_from_dumpsys_success(
      self, mock_get_dumpsys: MagicMock
  ) -> None:
    mock_get_dumpsys.return_value = """
IpClient.wlan0
  APF packet counters:
    COUNTER_NAME1: 123
    COUNTER_NAME2: 456
"""
    counters = get_apf_counters_from_dumpsys(self.mock_ad, "wlan0")
    self.assertEqual(counters, {"COUNTER_NAME1": 123, "COUNTER_NAME2": 456})

  @patch("net_tests_utils.host.python.adb_utils.get_dumpsys_for_service")
  def test_get_apf_counters_from_dumpsys_exceptions(
      self, mock_get_dumpsys: MagicMock
  ) -> None:
    test_cases = [
        "",
        "IpClient.wlan0\n",
        "IpClient.wlan0\n APF packet counters:\n",
        """
IpClient.wlan1
  APF packet counters:
    COUNTER_NAME1: 123
    COUNTER_NAME2: 456
""",
    ]

    for dumpsys_output in test_cases:
      mock_get_dumpsys.return_value = dumpsys_output
      with self.assertRaises(PatternNotFoundException):
        get_apf_counters_from_dumpsys(self.mock_ad, "wlan0")

  @patch("net_tests_utils.host.python.apf_utils.get_apf_counters_from_dumpsys")
  def test_get_apf_counter(self, mock_get_counters: MagicMock) -> None:
    iface = "wlan0"
    mock_get_counters.return_value = {
        "COUNTER_NAME1": 123,
        "COUNTER_NAME2": 456,
    }
    self.assertEqual(get_apf_counter(self.mock_ad, iface, "COUNTER_NAME1"), 123)
    # Not found
    self.assertEqual(get_apf_counter(self.mock_ad, iface, "COUNTER_NAME3"), 0)

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_hardware_address_success(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = """
46: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq ...
 link/ether 72:05:77:82:21:e0 brd ff:ff:ff:ff:ff:ff
"""
    mac_address = get_hardware_address(self.mock_ad, "wlan0")
    self.assertEqual(mac_address, "72:05:77:82:21:E0")

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_get_hardware_address_not_found(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = "Some output without MAC address"
    with self.assertRaises(PatternNotFoundException):
      get_hardware_address(self.mock_ad, "wlan0")

  @patch("net_tests_utils.host.python.apf_utils.get_hardware_address")
  @patch("net_tests_utils.host.python.apf_utils.send_raw_packet_downstream")
  def test_send_broadcast_empty_ethercat_packet(
      self,
      mock_send_raw_packet_downstream: MagicMock,
      mock_get_hardware_address: MagicMock,
  ) -> None:
    mock_get_hardware_address.return_value = "12:34:56:78:90:AB"
    send_broadcast_empty_ethercat_packet(self.mock_ad, "eth0")
    # Assuming you'll mock the packet construction part, verify calls to send_raw_packet_downstream.
    mock_send_raw_packet_downstream.assert_called_once()

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_send_raw_packet_downstream_success(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = ""  # Successful command output
    packet_type = "BEEF"
    iface_name = "eth0"
    dst_mac = "1234567890AB"
    packet_in_hex = "AABBCCDDEEFF"
    send_raw_packet_downstream(
        self.mock_ad, packet_type, iface_name, dst_mac, packet_in_hex
    )
    mock_adb_shell.assert_called_once_with(
        self.mock_ad,
        "cmd network_stack send-raw-packet-downstream"
        f" {packet_type} {iface_name} {dst_mac} {packet_in_hex}",
    )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_send_raw_packet_downstream_failure(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.return_value = (  # Unexpected command output
        "Any Unexpected Output"
    )
    with self.assertRaises(UnexpectedBehaviorError):
      send_raw_packet_downstream(
          self.mock_ad, "BEEF", "eth0", "1234567890AB", "AABBCCDDEEFF"
      )

  @patch("net_tests_utils.host.python.adb_utils.adb_shell")
  def test_send_raw_packet_downstream_unsupported(
      self, mock_adb_shell: MagicMock
  ) -> None:
    mock_adb_shell.side_effect = AdbError(
        cmd="", stdout="Unknown command", stderr="", ret_code=3
    )
    with self.assertRaises(UnsupportedOperationException):
      send_raw_packet_downstream(
          self.mock_ad, "BEEF", "eth0", "1234567890AB", "AABBCCDDEEFF"
      )


if __name__ == "__main__":
  unittest.main()

# Lint as: python3
"""Connectivity multi devices tests."""
import sys
from mobly import base_test
from mobly import test_runner
from mobly import utils
from mobly.controllers import android_device
from net_tests_utils.host.python import mdns_utils, tether_utils
from net_tests_utils.host.python.tether_utils import UpstreamType

CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE = "com.google.snippet.connectivity"


class ConnectivityMultiDevicesTest(base_test.BaseTestClass):

  def setup_class(self):
    # Declare that two Android devices are needed.
    self.clientDevice, self.serverDevice = self.register_controller(
        android_device, min_number=2
    )

    def setup_device(device):
      device.load_snippet(
          "connectivity_multi_devices_snippet",
          CONNECTIVITY_MULTI_DEVICES_SNIPPET_PACKAGE,
      )

    # Set up devices in parallel to save time.
    utils.concurrent_exec(
        setup_device,
        ((self.clientDevice,), (self.serverDevice,)),
        max_workers=2,
        raise_on_exception=True,
    )

  def test_hotspot_upstream_wifi(self):
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.WIFI
    )
    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
          self.serverDevice, self.clientDevice, UpstreamType.WIFI
      )
    finally:
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.WIFI
      )

  def test_hotspot_upstream_cellular(self):
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.CELLULAR
    )
    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
          self.serverDevice, self.clientDevice, UpstreamType.CELLULAR
      )
    finally:
      tether_utils.cleanup_tethering_for_upstream_type(
          self.serverDevice, UpstreamType.CELLULAR
      )

  def test_mdns_via_hotspot(self):
    tether_utils.assume_hotspot_test_preconditions(
        self.serverDevice, self.clientDevice, UpstreamType.NONE
    )
    try:
      # Connectivity of the client verified by asserting the validated capability.
      tether_utils.setup_hotspot_and_client_for_upstream_type(
        self.serverDevice, self.clientDevice, UpstreamType.NONE
      )
      mdns_utils.register_mdns_service_and_discover_resolve(
        self.clientDevice, self.serverDevice
      )
    finally:
      mdns_utils.cleanup_mdns_service(
        self.clientDevice, self.serverDevice
      )
      tether_utils.cleanup_tethering_for_upstream_type(
        self.serverDevice, UpstreamType.NONE
      )

if __name__ == "__main__":
  # Take test args
  if "--" in sys.argv:
    index = sys.argv.index("--")
    sys.argv = sys.argv[:1] + sys.argv[index + 1 :]
  test_runner.main()

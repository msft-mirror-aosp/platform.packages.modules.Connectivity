/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.networkstack.tethering;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_GLOBAL;
import static android.net.TetheringManager.CONNECTIVITY_SCOPE_LOCAL;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;

import static com.android.networkstack.tethering.util.PrefixUtils.asIpPrefix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ip.IpServer;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class PrivateAddressCoordinatorTest {
    private static final String TEST_IFNAME = "test0";

    @Mock private IpServer mHotspotIpServer;
    @Mock private IpServer mLocalHotspotIpServer;
    @Mock private IpServer mUsbIpServer;
    @Mock private IpServer mEthernetIpServer;
    @Mock private IpServer mWifiP2pIpServer;
    @Mock private Context mContext;
    @Mock private ConnectivityManager mConnectivityMgr;
    @Mock private TetheringConfiguration mConfig;

    private PrivateAddressCoordinator mPrivateAddressCoordinator;
    private final LinkAddress mBluetoothAddress = new LinkAddress("192.168.44.1/24");
    private final LinkAddress mLegacyWifiP2pAddress = new LinkAddress("192.168.49.1/24");
    private final Network mWifiNetwork = new Network(1);
    private final Network mMobileNetwork = new Network(2);
    private final Network mVpnNetwork = new Network(3);
    private final Network mMobileNetwork2 = new Network(4);
    private final Network mMobileNetwork3 = new Network(5);
    private final Network mMobileNetwork4 = new Network(6);
    private final Network mMobileNetwork5 = new Network(7);
    private final Network mMobileNetwork6 = new Network(8);
    private final Network[] mAllNetworks = {mMobileNetwork, mWifiNetwork, mVpnNetwork,
            mMobileNetwork2, mMobileNetwork3, mMobileNetwork4, mMobileNetwork5, mMobileNetwork6};
    private final ArrayList<IpPrefix> mTetheringPrefixes = new ArrayList<>(Arrays.asList(
            new IpPrefix("192.168.0.0/16"),
            new IpPrefix("172.16.0.0/12"),
            new IpPrefix("10.0.0.0/8")));

    private void setUpIpServers() throws Exception {
        when(mUsbIpServer.interfaceType()).thenReturn(TETHERING_USB);
        when(mEthernetIpServer.interfaceType()).thenReturn(TETHERING_ETHERNET);
        when(mHotspotIpServer.interfaceType()).thenReturn(TETHERING_WIFI);
        when(mLocalHotspotIpServer.interfaceType()).thenReturn(TETHERING_WIFI);
        when(mWifiP2pIpServer.interfaceType()).thenReturn(TETHERING_WIFI_P2P);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mConnectivityMgr);
        when(mContext.getSystemService(ConnectivityManager.class)).thenReturn(mConnectivityMgr);
        when(mConnectivityMgr.getAllNetworks()).thenReturn(mAllNetworks);
        when(mConfig.shouldEnableWifiP2pDedicatedIp()).thenReturn(false);
        when(mConfig.isRandomPrefixBaseEnabled()).thenReturn(false);
        setUpIpServers();
        mPrivateAddressCoordinator =
                spy(
                        new PrivateAddressCoordinator(
                                mConnectivityMgr::getAllNetworks,
                                mConfig.isRandomPrefixBaseEnabled(),
                                mConfig.shouldEnableWifiP2pDedicatedIp()));
    }

    private LinkAddress requestDownstreamAddress(final IpServer ipServer, int scope,
            boolean useLastAddress) {
        final LinkAddress address = mPrivateAddressCoordinator.requestDownstreamAddress(
                ipServer, scope, useLastAddress);
        when(ipServer.getAddress()).thenReturn(address);
        return address;
    }

    private void updateUpstreamPrefix(UpstreamNetworkState ns) {
        mPrivateAddressCoordinator.updateUpstreamPrefix(
                ns.linkProperties, ns.networkCapabilities, ns.network);
    }

    @Test
    public void testRequestDownstreamAddressWithoutUsingLastAddress() throws Exception {
        final IpPrefix bluetoothPrefix = asIpPrefix(mBluetoothAddress);
        final LinkAddress address = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, false /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(address);
        assertNotEquals(hotspotPrefix, bluetoothPrefix);

        final LinkAddress newAddress = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, false /* useLastAddress */);
        final IpPrefix newHotspotPrefix = asIpPrefix(newAddress);
        assertNotEquals(hotspotPrefix, newHotspotPrefix);
        assertNotEquals(bluetoothPrefix, newHotspotPrefix);

        final LinkAddress usbAddress = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, false /* useLastAddress */);
        final IpPrefix usbPrefix = asIpPrefix(usbAddress);
        assertNotEquals(usbPrefix, bluetoothPrefix);
        assertNotEquals(usbPrefix, newHotspotPrefix);

        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);
    }

    @Test
    public void testReservedPrefix() throws Exception {
        // - Test bluetooth prefix is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(mBluetoothAddress.getAddress().getAddress()));
        final LinkAddress hotspotAddress = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, false /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(hotspotAddress);
        assertNotEquals(asIpPrefix(mBluetoothAddress), hotspotPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);

        // - Test previous enabled hotspot prefix(cached prefix) is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(hotspotAddress.getAddress().getAddress()));
        final LinkAddress usbAddress = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, false /* useLastAddress */);
        final IpPrefix usbPrefix = asIpPrefix(usbAddress);
        assertNotEquals(asIpPrefix(mBluetoothAddress), usbPrefix);
        assertNotEquals(hotspotPrefix, usbPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);

        // - Test wifi p2p prefix is reserved.
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(mLegacyWifiP2pAddress.getAddress().getAddress()));
        final LinkAddress etherAddress = requestDownstreamAddress(mEthernetIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, false /* useLastAddress */);
        final IpPrefix etherPrefix = asIpPrefix(etherAddress);
        assertNotEquals(asIpPrefix(mLegacyWifiP2pAddress), etherPrefix);
        assertNotEquals(asIpPrefix(mBluetoothAddress), etherPrefix);
        assertNotEquals(hotspotPrefix, etherPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mEthernetIpServer);
    }

    @Test
    public void testRequestLastDownstreamAddress() throws Exception {
        final LinkAddress hotspotAddress = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);

        final LinkAddress usbAddress = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);

        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
        mPrivateAddressCoordinator.releaseDownstream(mUsbIpServer);

        final LinkAddress newHotspotAddress = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals(hotspotAddress, newHotspotAddress);
        final LinkAddress newUsbAddress = requestDownstreamAddress(mUsbIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertEquals(usbAddress, newUsbAddress);

        final UpstreamNetworkState wifiUpstream = buildUpstreamNetworkState(mWifiNetwork,
                new LinkAddress("192.168.88.23/16"), null,
                makeNetworkCapabilities(TRANSPORT_WIFI));
        updateUpstreamPrefix(wifiUpstream);
        verify(mHotspotIpServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        verify(mUsbIpServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
    }

    private UpstreamNetworkState buildUpstreamNetworkState(final Network network,
            final LinkAddress v4Addr, final LinkAddress v6Addr, final NetworkCapabilities cap) {
        final LinkProperties prop = new LinkProperties();
        prop.setInterfaceName(TEST_IFNAME);
        if (v4Addr != null) prop.addLinkAddress(v4Addr);

        if (v6Addr != null) prop.addLinkAddress(v6Addr);

        return new UpstreamNetworkState(prop, cap, network);
    }

    private NetworkCapabilities makeNetworkCapabilities(final int transportType) {
        final NetworkCapabilities cap = new NetworkCapabilities();
        cap.addTransportType(transportType);
        if (transportType == TRANSPORT_VPN) {
            cap.removeCapability(NET_CAPABILITY_NOT_VPN);
        }

        return cap;
    }

    @Test
    public void testChooseDownstreamAddress_noUpstreamConflicts() throws Exception {
        LinkAddress address = new LinkAddress("192.168.42.42/24");
        UpstreamNetworkState ns = buildUpstreamNetworkState(mMobileNetwork, address, null, null);
        updateUpstreamPrefix(ns);
        // try to look for a /24 in upstream that does not conflict with upstream -> impossible.
        assertNull(mPrivateAddressCoordinator.chooseDownstreamAddress(asIpPrefix(address)));

        IpPrefix prefix = new IpPrefix("192.168.0.0/16");
        LinkAddress chosenAddress = mPrivateAddressCoordinator.chooseDownstreamAddress(prefix);
        assertNotNull(chosenAddress);
        assertTrue(prefix.containsPrefix(asIpPrefix(chosenAddress)));
    }

    @Test
    public void testChooseDownstreamAddress_excludesWellKnownPrefixes() throws Exception {
        IpPrefix prefix = new IpPrefix("192.168.0.0/24");
        assertNull(mPrivateAddressCoordinator.chooseDownstreamAddress(prefix));
        prefix = new IpPrefix("192.168.100.0/24");
        assertNull(mPrivateAddressCoordinator.chooseDownstreamAddress(prefix));
        prefix = new IpPrefix("10.3.0.0/16");
        assertNull(mPrivateAddressCoordinator.chooseDownstreamAddress(prefix));
    }

    private void verifyNotifyConflictAndRelease(final IpServer ipServer) throws Exception {
        verify(ipServer).sendMessage(IpServer.CMD_NOTIFY_PREFIX_CONFLICT);
        mPrivateAddressCoordinator.releaseDownstream(ipServer);
        reset(ipServer);
        setUpIpServers();
    }

    private int getSubAddress(final byte... ipv4Address) {
        assertEquals(4, ipv4Address.length);

        int subnet = Byte.toUnsignedInt(ipv4Address[2]);
        return (subnet << 8) + ipv4Address[3];
    }

    private void assertReseveredWifiP2pPrefix() throws Exception {
        LinkAddress address = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        final IpPrefix hotspotPrefix = asIpPrefix(address);
        final IpPrefix legacyWifiP2pPrefix = asIpPrefix(mLegacyWifiP2pAddress);
        assertNotEquals(legacyWifiP2pPrefix, hotspotPrefix);
        mPrivateAddressCoordinator.releaseDownstream(mHotspotIpServer);
    }

    @Test
    public void testEnableLegacyWifiP2PAddress() throws Exception {
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(
                getSubAddress(mLegacyWifiP2pAddress.getAddress().getAddress()));
        // No matter #shouldEnableWifiP2pDedicatedIp() is enabled or not, legacy wifi p2p prefix
        // is resevered.
        assertReseveredWifiP2pPrefix();

        when(mConfig.shouldEnableWifiP2pDedicatedIp()).thenReturn(true);
        assertReseveredWifiP2pPrefix();

        // If #shouldEnableWifiP2pDedicatedIp() is enabled, wifi P2P gets the configured address.
        LinkAddress address = requestDownstreamAddress(mWifiP2pIpServer,
                CONNECTIVITY_SCOPE_LOCAL, true /* useLastAddress */);
        assertEquals(mLegacyWifiP2pAddress, address);
        mPrivateAddressCoordinator.releaseDownstream(mWifiP2pIpServer);
    }

    @Test
    public void testEnableSapAndLohsConcurrently() throws Exception {
        final LinkAddress hotspotAddress = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, true /* useLastAddress */);
        assertNotNull(hotspotAddress);

        final LinkAddress localHotspotAddress = requestDownstreamAddress(mLocalHotspotIpServer,
                CONNECTIVITY_SCOPE_LOCAL, true /* useLastAddress */);
        assertNotNull(localHotspotAddress);

        final IpPrefix hotspotPrefix = asIpPrefix(hotspotAddress);
        final IpPrefix localHotspotPrefix = asIpPrefix(localHotspotAddress);
        assertFalse(hotspotPrefix.containsPrefix(localHotspotPrefix));
        assertFalse(localHotspotPrefix.containsPrefix(hotspotPrefix));
    }

    @Test
    public void testStartedPrefixRange() throws Exception {
        when(mConfig.isRandomPrefixBaseEnabled()).thenReturn(true);

        startedPrefixBaseTest("192.168.0.0/16", 0);

        startedPrefixBaseTest("192.168.0.0/16", 1);

        startedPrefixBaseTest("192.168.0.0/16", 0xffff);

        startedPrefixBaseTest("172.16.0.0/12", 0x10000);

        startedPrefixBaseTest("172.16.0.0/12", 0x11111);

        startedPrefixBaseTest("172.16.0.0/12", 0xfffff);

        startedPrefixBaseTest("10.0.0.0/8", 0x100000);

        startedPrefixBaseTest("10.0.0.0/8", 0x1fffff);

        startedPrefixBaseTest("10.0.0.0/8", 0xffffff);

        startedPrefixBaseTest("192.168.0.0/16", 0x1000000);
    }

    private void startedPrefixBaseTest(final String expected, final int randomIntForPrefixBase)
            throws Exception {
        mPrivateAddressCoordinator =
                spy(
                        new PrivateAddressCoordinator(
                                mConnectivityMgr::getAllNetworks,
                                mConfig.isRandomPrefixBaseEnabled(),
                                mConfig.shouldEnableWifiP2pDedicatedIp()));
        when(mPrivateAddressCoordinator.getRandomInt()).thenReturn(randomIntForPrefixBase);
        final LinkAddress address = requestDownstreamAddress(mHotspotIpServer,
                CONNECTIVITY_SCOPE_GLOBAL, false /* useLastAddress */);
        final IpPrefix prefixBase = new IpPrefix(expected);
        assertTrue(address + " is not part of " + prefixBase,
                prefixBase.containsPrefix(asIpPrefix(address)));

    }
}

/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.thread;

import android.annotation.Nullable;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.util.SocketUtils;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.android.net.module.util.LinkPropertiesUtils.CompareResult;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.RtNetlinkAddressMessage;
import com.android.server.thread.openthread.Ipv6AddressInfo;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Controller for virtual/tunnel network interfaces. */
public class TunInterfaceController {
    private static final String TAG = "TunIfController";
    private static final long INFINITE_LIFETIME = 0xffffffffL;
    static final int MTU = 1280;

    static {
        System.loadLibrary("service-thread-jni");
    }

    private final String mIfName;
    private final LinkProperties mLinkProperties = new LinkProperties();
    private ParcelFileDescriptor mParcelTunFd;
    private FileDescriptor mNetlinkSocket;
    private static int sNetlinkSeqNo = 0;

    /** Creates a new {@link TunInterfaceController} instance for given interface. */
    public TunInterfaceController(String interfaceName) {
        mIfName = interfaceName;
        mLinkProperties.setInterfaceName(mIfName);
        mLinkProperties.setMtu(MTU);
    }

    /** Returns link properties of the Thread TUN interface. */
    public LinkProperties getLinkProperties() {
        return mLinkProperties;
    }

    /**
     * Creates the tunnel interface.
     *
     * @throws IOException if failed to create the interface
     */
    public void createTunInterface() throws IOException {
        mParcelTunFd = ParcelFileDescriptor.adoptFd(nativeCreateTunInterface(mIfName, MTU));
        try {
            mNetlinkSocket = NetlinkUtils.netlinkSocketForProto(OsConstants.NETLINK_ROUTE);
        } catch (ErrnoException e) {
            throw new IOException("Failed to create netlink socket", e);
        }
    }

    public void destroyTunInterface() {
        try {
            mParcelTunFd.close();
            SocketUtils.closeSocket(mNetlinkSocket);
        } catch (IOException e) {
            // Should never fail
        }
        mParcelTunFd = null;
        mNetlinkSocket = null;
    }

    /** Returns the FD of the tunnel interface. */
    @Nullable
    public ParcelFileDescriptor getTunFd() {
        return mParcelTunFd;
    }

    private native int nativeCreateTunInterface(String interfaceName, int mtu) throws IOException;

    /** Sets the interface up or down according to {@code isUp}. */
    public void setInterfaceUp(boolean isUp) throws IOException {
        if (!isUp) {
            for (LinkAddress address : mLinkProperties.getAllLinkAddresses()) {
                removeAddress(address);
            }
        }
        nativeSetInterfaceUp(mIfName, isUp);
    }

    private native void nativeSetInterfaceUp(String interfaceName, boolean isUp) throws IOException;

    /** Adds a new address to the interface. */
    public void addAddress(LinkAddress address) {
        Log.d(TAG, "Adding address " + address + " with flags: " + address.getFlags());

        long validLifetimeSeconds;
        long preferredLifetimeSeconds;

        if (address.getDeprecationTime() == LinkAddress.LIFETIME_PERMANENT
                || address.getDeprecationTime() == LinkAddress.LIFETIME_UNKNOWN) {
            validLifetimeSeconds = INFINITE_LIFETIME;
        } else {
            validLifetimeSeconds =
                    Math.max(
                            (address.getDeprecationTime() - SystemClock.elapsedRealtime()) / 1000L,
                            0L);
        }

        if (address.getExpirationTime() == LinkAddress.LIFETIME_PERMANENT
                || address.getExpirationTime() == LinkAddress.LIFETIME_UNKNOWN) {
            preferredLifetimeSeconds = INFINITE_LIFETIME;
        } else {
            preferredLifetimeSeconds =
                    Math.max(
                            (address.getExpirationTime() - SystemClock.elapsedRealtime()) / 1000L,
                            0L);
        }

        byte[] message =
                RtNetlinkAddressMessage.newRtmNewAddressMessage(
                        sNetlinkSeqNo++,
                        address.getAddress(),
                        (short) address.getPrefixLength(),
                        address.getFlags(),
                        (byte) address.getScope(),
                        Os.if_nametoindex(mIfName),
                        validLifetimeSeconds,
                        preferredLifetimeSeconds);
        try {
            Os.write(mNetlinkSocket, message, 0, message.length);
        } catch (ErrnoException | InterruptedIOException e) {
            Log.e(TAG, "Failed to add address " + address, e);
            return;
        }
        mLinkProperties.addLinkAddress(address);
        mLinkProperties.addRoute(getRouteForAddress(address));
    }

    /** Removes an address from the interface. */
    public void removeAddress(LinkAddress address) {
        Log.d(TAG, "Removing address " + address);
        byte[] message =
                RtNetlinkAddressMessage.newRtmDelAddressMessage(
                        sNetlinkSeqNo++,
                        address.getAddress(),
                        (short) address.getPrefixLength(),
                        Os.if_nametoindex(mIfName));

        // Intentionally update the mLinkProperties before send netlink message because the
        // address is already removed from ot-daemon and apps can't reach to the address even
        // when the netlink request below fails
        mLinkProperties.removeLinkAddress(address);
        mLinkProperties.removeRoute(getRouteForAddress(address));
        try {
            Os.write(mNetlinkSocket, message, 0, message.length);
        } catch (ErrnoException | InterruptedIOException e) {
            Log.e(TAG, "Failed to remove address " + address, e);
        }
    }

    public void updateAddresses(List<Ipv6AddressInfo> addressInfoList) {
        final List<LinkAddress> newLinkAddresses = new ArrayList<>();
        boolean hasActiveOmrAddress = false;

        for (Ipv6AddressInfo addressInfo : addressInfoList) {
            if (addressInfo.isActiveOmr) {
                hasActiveOmrAddress = true;
                break;
            }
        }

        for (Ipv6AddressInfo addressInfo : addressInfoList) {
            InetAddress address = addressInfoToInetAddress(addressInfo);
            if (address.isMulticastAddress()) {
                // TODO: Logging here will create repeated logs for a single multicast address, and
                // it currently is not mandatory for debugging. Add log for ignored multicast
                // address when necessary.
                continue;
            }
            newLinkAddresses.add(newLinkAddress(addressInfo, hasActiveOmrAddress));
        }

        final CompareResult<LinkAddress> addressDiff =
                new CompareResult<>(mLinkProperties.getAllLinkAddresses(), newLinkAddresses);
        for (LinkAddress linkAddress : addressDiff.removed) {
            removeAddress(linkAddress);
        }
        for (LinkAddress linkAddress : addressDiff.added) {
            addAddress(linkAddress);
        }
    }

    private RouteInfo getRouteForAddress(LinkAddress linkAddress) {
        return new RouteInfo(
                new IpPrefix(linkAddress.getAddress(), linkAddress.getPrefixLength()),
                null,
                mIfName,
                RouteInfo.RTN_UNICAST,
                MTU);
    }

    /** Called by {@link ThreadNetworkControllerService} to do clean up when ot-daemon is dead. */
    public void onOtDaemonDied() {
        try {
            setInterfaceUp(false);
        } catch (IOException e) {
            Log.e(TAG, "Failed to set Thread TUN interface down");
        }
    }

    private static InetAddress addressInfoToInetAddress(Ipv6AddressInfo addressInfo) {
        return bytesToInet6Address(addressInfo.address);
    }

    private static Inet6Address bytesToInet6Address(byte[] addressBytes) {
        try {
            return (Inet6Address) Inet6Address.getByAddress(addressBytes);
        } catch (UnknownHostException e) {
            // This is unlikely to happen unless the Thread daemon is critically broken
            return null;
        }
    }

    private static LinkAddress newLinkAddress(
            Ipv6AddressInfo addressInfo, boolean hasActiveOmrAddress) {
        // Mesh-local addresses and OMR address have the same scope, to distinguish them we set
        // mesh-local addresses as deprecated when there is an active OMR address.
        // For OMR address and link-local address we only use the value isPreferred set by
        // ot-daemon.
        boolean isPreferred = addressInfo.isPreferred;
        if (addressInfo.isMeshLocal && hasActiveOmrAddress) {
            isPreferred = false;
        }

        final long deprecationTimeMillis =
                isPreferred ? LinkAddress.LIFETIME_PERMANENT : SystemClock.elapsedRealtime();

        final InetAddress address = addressInfoToInetAddress(addressInfo);

        // flags and scope will be adjusted automatically depending on the address and
        // its lifetimes.
        return new LinkAddress(
                address,
                addressInfo.prefixLength,
                0 /* flags */,
                0 /* scope */,
                deprecationTimeMillis,
                LinkAddress.LIFETIME_PERMANENT /* expirationTime */);
    }
}

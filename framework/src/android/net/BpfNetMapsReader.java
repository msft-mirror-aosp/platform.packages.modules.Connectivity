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

package android.net;

import static android.net.BpfNetMapsConstants.CONFIGURATION_MAP_PATH;
import static android.net.BpfNetMapsConstants.UID_OWNER_MAP_PATH;
import static android.net.BpfNetMapsConstants.UID_RULES_CONFIGURATION_KEY;
import static android.net.BpfNetMapsUtils.getMatchByFirewallChain;
import static android.net.BpfNetMapsUtils.isFirewallAllowList;
import static android.net.BpfNetMapsUtils.throwIfPreT;
import static android.net.ConnectivityManager.FIREWALL_RULE_ALLOW;
import static android.net.ConnectivityManager.FIREWALL_RULE_DENY;

import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.os.Build;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.Os;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.IBpfMap;
import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.S32;
import com.android.net.module.util.Struct.U32;

/**
 * A helper class to *read* java BpfMaps.
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)  // BPF maps were only mainlined in T
public class BpfNetMapsReader {
    // Locally store the handle of bpf maps. The FileDescriptors are statically cached inside the
    // BpfMap implementation.

    // Bpf map to store various networking configurations, the format of the value is different
    // for different keys. See BpfNetMapsConstants#*_CONFIGURATION_KEY for keys.
    private final IBpfMap<S32, U32> mConfigurationMap;
    // Bpf map to store per uid traffic control configurations.
    // See {@link UidOwnerValue} for more detail.
    private final IBpfMap<S32, UidOwnerValue> mUidOwnerMap;
    private final Dependencies mDeps;

    public BpfNetMapsReader() {
        this(new Dependencies());
    }

    @VisibleForTesting
    public BpfNetMapsReader(@NonNull Dependencies deps) {
        if (!SdkLevel.isAtLeastT()) {
            throw new UnsupportedOperationException(
                    BpfNetMapsReader.class.getSimpleName() + " is not supported below Android T");
        }
        mDeps = deps;
        mConfigurationMap = mDeps.getConfigurationMap();
        mUidOwnerMap = mDeps.getUidOwnerMap();
    }

    /**
     * Dependencies of BpfNetMapReader, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /** Get the configuration map. */
        public IBpfMap<S32, U32> getConfigurationMap() {
            try {
                return new BpfMap<>(CONFIGURATION_MAP_PATH, BpfMap.BPF_F_RDONLY,
                        S32.class, U32.class);
            } catch (ErrnoException e) {
                throw new IllegalStateException("Cannot open configuration map", e);
            }
        }

        /** Get the uid owner map. */
        public IBpfMap<S32, UidOwnerValue> getUidOwnerMap() {
            try {
                return new BpfMap<>(UID_OWNER_MAP_PATH, BpfMap.BPF_F_RDONLY,
                        S32.class, UidOwnerValue.class);
            } catch (ErrnoException e) {
                throw new IllegalStateException("Cannot open uid owner map", e);
            }
        }
    }

    /**
     * Get the specified firewall chain's status.
     *
     * @param chain target chain
     * @return {@code true} if chain is enabled, {@code false} if chain is not enabled.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public boolean isChainEnabled(final int chain) {
        return isChainEnabled(mConfigurationMap, chain);
    }

    /**
     * Get firewall rule of specified firewall chain on specified uid.
     *
     * @param chain target chain
     * @param uid        target uid
     * @return either {@link ConnectivityManager#FIREWALL_RULE_ALLOW} or
     *         {@link ConnectivityManager#FIREWALL_RULE_DENY}.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public int getUidRule(final int chain, final int uid) {
        return getUidRule(mUidOwnerMap, chain, uid);
    }

    /**
     * Get the specified firewall chain's status.
     *
     * @param configurationMap target configurationMap
     * @param chain target chain
     * @return {@code true} if chain is enabled, {@code false} if chain is not enabled.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public static boolean isChainEnabled(
            final IBpfMap<Struct.S32, Struct.U32> configurationMap, final int chain) {
        throwIfPreT("isChainEnabled is not available on pre-T devices");

        final long match = getMatchByFirewallChain(chain);
        try {
            final Struct.U32 config = configurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
            return (config.val & match) != 0;
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to get firewall chain status: " + Os.strerror(e.errno));
        }
    }

    /**
     * Get firewall rule of specified firewall chain on specified uid.
     *
     * @param uidOwnerMap target uidOwnerMap.
     * @param chain target chain.
     * @param uid target uid.
     * @return either FIREWALL_RULE_ALLOW or FIREWALL_RULE_DENY
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException      in case of failure, with an error code indicating the
     *                                       cause of the failure.
     */
    public static int getUidRule(final IBpfMap<Struct.S32, UidOwnerValue> uidOwnerMap,
            final int chain, final int uid) {
        throwIfPreT("getUidRule is not available on pre-T devices");

        final long match = getMatchByFirewallChain(chain);
        final boolean isAllowList = isFirewallAllowList(chain);
        try {
            final UidOwnerValue uidMatch = uidOwnerMap.getValue(new Struct.S32(uid));
            final boolean isMatchEnabled = uidMatch != null && (uidMatch.rule & match) != 0;
            return isMatchEnabled == isAllowList ? FIREWALL_RULE_ALLOW : FIREWALL_RULE_DENY;
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to get uid rule status: " + Os.strerror(e.errno));
        }
    }
}

/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.net.module.util;

import static android.system.OsConstants.EBUSY;

import static com.android.testutils.MiscAsserts.assertThrows;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.INetd;
import android.net.InterfaceConfigurationParcel;
import android.os.RemoteException;
import android.os.ServiceSpecificException;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NetdUtilsTest {
    @Mock private INetd mNetd;

    private static final String IFACE = "TEST_IFACE";
    private static final int TEST_NET_ID = 123;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    private void setupFlagsForInterfaceConfiguration(String[] flags) throws Exception {
        final InterfaceConfigurationParcel config = new InterfaceConfigurationParcel();
        config.flags = flags;
        when(mNetd.interfaceGetCfg(eq(IFACE))).thenReturn(config);
    }

    private void verifyMethodsAndArgumentsOfSetInterface(boolean ifaceUp) throws Exception {
        final String[] flagsContainDownAndUp = new String[] {"flagA", "down", "flagB", "up"};
        final String[] flagsForInterfaceDown = new String[] {"flagA", "down", "flagB"};
        final String[] flagsForInterfaceUp = new String[] {"flagA", "up", "flagB"};
        final String[] expectedFinalFlags;
        setupFlagsForInterfaceConfiguration(flagsContainDownAndUp);
        if (ifaceUp) {
            // "down" flag will be removed from flagsContainDownAndUp when interface is up. Set
            // expectedFinalFlags to flagsForInterfaceUp.
            expectedFinalFlags = flagsForInterfaceUp;
            NetdUtils.setInterfaceUp(mNetd, IFACE);
        } else {
            // "up" flag will be removed from flagsContainDownAndUp when interface is down. Set
            // expectedFinalFlags to flagsForInterfaceDown.
            expectedFinalFlags = flagsForInterfaceDown;
            NetdUtils.setInterfaceDown(mNetd, IFACE);
        }
        verify(mNetd).interfaceSetCfg(
                argThat(config ->
                        // Check if actual flags are the same as expected flags.
                        // TODO: Have a function in MiscAsserts to check if two arrays are the same.
                        CollectionUtils.all(Arrays.asList(expectedFinalFlags),
                                flag -> Arrays.asList(config.flags).contains(flag))
                        && CollectionUtils.all(Arrays.asList(config.flags),
                                flag -> Arrays.asList(expectedFinalFlags).contains(flag))));
    }

    @Test
    public void testSetInterfaceUp() throws Exception {
        verifyMethodsAndArgumentsOfSetInterface(true /* ifaceUp */);
    }

    @Test
    public void testSetInterfaceDown() throws Exception {
        verifyMethodsAndArgumentsOfSetInterface(false /* ifaceUp */);
    }

    @Test
    public void testRemoveAndAddFlags() throws Exception {
        final String[] flags = new String[] {"flagA", "down", "flagB"};
        // Add an invalid flag and expect to get an IllegalStateException.
        assertThrows(IllegalStateException.class,
                () -> NetdUtils.removeAndAddFlags(flags, "down" /* remove */, "u p" /* add */));
    }

    private void setNetworkAddInterfaceOutcome(final Exception cause, int numLoops)
            throws Exception {
        // This cannot be an int because local variables referenced from a lambda expression must
        // be final or effectively final.
        final Counter myCounter = new Counter();
        doAnswer((invocation) -> {
            myCounter.count();
            if (myCounter.isCounterReached(numLoops)) {
                if (cause == null) return null;

                throw cause;
            }

            throw new ServiceSpecificException(EBUSY);
        }).when(mNetd).networkAddInterface(TEST_NET_ID, IFACE);
    }

    class Counter {
        private int mValue = 0;

        private void count() {
            mValue++;
        }

        private boolean isCounterReached(int target) {
            return mValue >= target;
        }
    }

    @Test
    public void testNetworkAddInterfaceSuccessful() throws Exception {
        // Expect #networkAddInterface successful at first tries.
        verifyNetworkAddInterfaceSucceeds(1);

        // Expect #networkAddInterface successful after 10 tries.
        verifyNetworkAddInterfaceSucceeds(10);
    }

    private void runNetworkAddInterfaceWithServiceSpecificException(int expectedTries,
            int expectedCode) throws Exception {
        setNetworkAddInterfaceOutcome(new ServiceSpecificException(expectedCode), expectedTries);

        try {
            NetdUtils.networkAddInterface(mNetd, TEST_NET_ID, IFACE,
                    20 /* maxAttempts */, 0 /* pollingIntervalMs */);
            fail("Expect throw ServiceSpecificException");
        } catch (ServiceSpecificException e) {
            assertEquals(e.errorCode, expectedCode);
        }

        verifyNetworkAddInterfaceFails(expectedTries);
        reset(mNetd);
    }

    private void runNetworkAddInterfaceWithRemoteException(int expectedTries) throws Exception {
        setNetworkAddInterfaceOutcome(new RemoteException(), expectedTries);

        try {
            NetdUtils.networkAddInterface(mNetd, TEST_NET_ID, IFACE,
                    20 /* maxAttempts */, 0 /* pollingIntervalMs */);
            fail("Expect throw RemoteException");
        } catch (RemoteException e) { }

        verifyNetworkAddInterfaceFails(expectedTries);
        reset(mNetd);
    }

    private void verifyNetworkAddInterfaceFails(int expectedTries) throws Exception {
        verify(mNetd, times(expectedTries)).networkAddInterface(TEST_NET_ID, IFACE);
        verifyNoMoreInteractions(mNetd);
    }

    private void verifyNetworkAddInterfaceSucceeds(int expectedTries) throws Exception {
        setNetworkAddInterfaceOutcome(null, expectedTries);

        NetdUtils.networkAddInterface(mNetd, TEST_NET_ID, IFACE,
                20 /* maxAttempts */, 0 /* pollingIntervalMs */);
        verify(mNetd, times(expectedTries)).networkAddInterface(TEST_NET_ID, IFACE);
        verifyNoMoreInteractions(mNetd);
        reset(mNetd);
    }

    @Test
    public void testFailOnNetworkAddInterface() throws Exception {
        // Test throwing ServiceSpecificException with EBUSY failure.
        runNetworkAddInterfaceWithServiceSpecificException(20, EBUSY);

        // Test throwing ServiceSpecificException with unexpectedError.
        final int unexpectedError = 999;
        runNetworkAddInterfaceWithServiceSpecificException(1, unexpectedError);

        // Test throwing ServiceSpecificException with unexpectedError after 7 tries.
        runNetworkAddInterfaceWithServiceSpecificException(7, unexpectedError);

        // Test throwing RemoteException.
        runNetworkAddInterfaceWithRemoteException(1);

        // Test throwing RemoteException after 3 tries.
        runNetworkAddInterfaceWithRemoteException(3);
    }

    @Test
    public void testNetdUtilsHasFlag() throws Exception {
        final String[] flags = new String[] {"up", "broadcast", "running", "multicast"};
        setupFlagsForInterfaceConfiguration(flags);

        // Set interface up.
        NetdUtils.setInterfaceUp(mNetd, IFACE);
        final ArgumentCaptor<InterfaceConfigurationParcel> arg =
                ArgumentCaptor.forClass(InterfaceConfigurationParcel.class);
        verify(mNetd, times(1)).interfaceSetCfg(arg.capture());

        final InterfaceConfigurationParcel p = arg.getValue();
        assertTrue(NetdUtils.hasFlag(p, "up"));
        assertTrue(NetdUtils.hasFlag(p, "running"));
        assertTrue(NetdUtils.hasFlag(p, "broadcast"));
        assertTrue(NetdUtils.hasFlag(p, "multicast"));
        assertFalse(NetdUtils.hasFlag(p, "down"));
    }

    @Test
    public void testNetdUtilsHasFlag_flagContainsSpace() throws Exception {
        final String[] flags = new String[] {"up", "broadcast", "running", "multicast"};
        setupFlagsForInterfaceConfiguration(flags);

        // Set interface up.
        NetdUtils.setInterfaceUp(mNetd, IFACE);
        final ArgumentCaptor<InterfaceConfigurationParcel> arg =
                ArgumentCaptor.forClass(InterfaceConfigurationParcel.class);
        verify(mNetd, times(1)).interfaceSetCfg(arg.capture());

        final InterfaceConfigurationParcel p = arg.getValue();
        assertThrows(IllegalArgumentException.class, () -> NetdUtils.hasFlag(p, "up "));
    }

    @Test
    public void testNetdUtilsHasFlag_UppercaseString() throws Exception {
        final String[] flags = new String[] {"up", "broadcast", "running", "multicast"};
        setupFlagsForInterfaceConfiguration(flags);

        // Set interface up.
        NetdUtils.setInterfaceUp(mNetd, IFACE);
        final ArgumentCaptor<InterfaceConfigurationParcel> arg =
                ArgumentCaptor.forClass(InterfaceConfigurationParcel.class);
        verify(mNetd, times(1)).interfaceSetCfg(arg.capture());

        final InterfaceConfigurationParcel p = arg.getValue();
        assertFalse(NetdUtils.hasFlag(p, "UP"));
        assertFalse(NetdUtils.hasFlag(p, "BROADCAST"));
        assertFalse(NetdUtils.hasFlag(p, "RUNNING"));
        assertFalse(NetdUtils.hasFlag(p, "MULTICAST"));
    }
}

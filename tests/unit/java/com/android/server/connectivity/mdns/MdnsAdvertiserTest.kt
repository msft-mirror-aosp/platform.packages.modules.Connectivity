/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity.mdns

import android.net.InetAddresses.parseNumericAddress
import android.net.LinkAddress
import android.net.Network
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.android.server.connectivity.mdns.MdnsAdvertiser.AdvertiserCallback
import com.android.server.connectivity.mdns.MdnsSocketProvider.SocketCallback
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.waitForIdle
import java.util.Objects
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.argThat
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val SERVICE_ID_1 = 1
private const val SERVICE_ID_2 = 2
private const val TIMEOUT_MS = 10_000L
private val TEST_ADDR = parseNumericAddress("2001:db8::123")
private val TEST_LINKADDR = LinkAddress(TEST_ADDR, 64 /* prefixLength */)
private val TEST_NETWORK_1 = mock(Network::class.java)
private val TEST_NETWORK_2 = mock(Network::class.java)
private val TEST_HOSTNAME = arrayOf("Android_test", "local")

private val SERVICE_1 = NsdServiceInfo("TestServiceName", "_advertisertest._tcp").apply {
    port = 12345
    host = TEST_ADDR
    network = TEST_NETWORK_1
}

private val ALL_NETWORKS_SERVICE = NsdServiceInfo("TestServiceName", "_advertisertest._tcp").apply {
    port = 12345
    host = TEST_ADDR
    network = null
}

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsAdvertiserTest {
    private val thread = HandlerThread(MdnsAdvertiserTest::class.simpleName)
    private val handler by lazy { Handler(thread.looper) }
    private val socketProvider = mock(MdnsSocketProvider::class.java)
    private val cb = mock(AdvertiserCallback::class.java)

    private val mockSocket1 = mock(MdnsInterfaceSocket::class.java)
    private val mockSocket2 = mock(MdnsInterfaceSocket::class.java)
    private val mockInterfaceAdvertiser1 = mock(MdnsInterfaceAdvertiser::class.java)
    private val mockInterfaceAdvertiser2 = mock(MdnsInterfaceAdvertiser::class.java)
    private val mockDeps = mock(MdnsAdvertiser.Dependencies::class.java)

    @Before
    fun setUp() {
        thread.start()
        doReturn(TEST_HOSTNAME).`when`(mockDeps).generateHostname()
        doReturn(mockInterfaceAdvertiser1).`when`(mockDeps).makeAdvertiser(eq(mockSocket1),
                any(), any(), any(), any(), eq(TEST_HOSTNAME)
        )
        doReturn(mockInterfaceAdvertiser2).`when`(mockDeps).makeAdvertiser(eq(mockSocket2),
                any(), any(), any(), any(), eq(TEST_HOSTNAME)
        )
        doReturn(true).`when`(mockInterfaceAdvertiser1).isProbing(anyInt())
        doReturn(true).`when`(mockInterfaceAdvertiser2).isProbing(anyInt())
    }

    @After
    fun tearDown() {
        thread.quitSafely()
    }

    @Test
    fun testAddService_OneNetwork() {
        val advertiser = MdnsAdvertiser(thread.looper, socketProvider, cb, mockDeps)
        postSync { advertiser.addService(SERVICE_ID_1, SERVICE_1) }

        val socketCbCaptor = ArgumentCaptor.forClass(SocketCallback::class.java)
        verify(socketProvider).requestSocket(eq(TEST_NETWORK_1), socketCbCaptor.capture())

        val socketCb = socketCbCaptor.value
        postSync { socketCb.onSocketCreated(TEST_NETWORK_1, mockSocket1, listOf(TEST_LINKADDR)) }

        val intAdvCbCaptor = ArgumentCaptor.forClass(MdnsInterfaceAdvertiser.Callback::class.java)
        verify(mockDeps).makeAdvertiser(
            eq(mockSocket1),
            eq(listOf(TEST_LINKADDR)),
            eq(thread.looper),
            any(),
            intAdvCbCaptor.capture(),
            eq(TEST_HOSTNAME)
        )

        doReturn(false).`when`(mockInterfaceAdvertiser1).isProbing(SERVICE_ID_1)
        postSync { intAdvCbCaptor.value.onRegisterServiceSucceeded(
                mockInterfaceAdvertiser1, SERVICE_ID_1) }
        verify(cb).onRegisterServiceSucceeded(eq(SERVICE_ID_1), argThat { it.matches(SERVICE_1) })

        postSync { socketCb.onInterfaceDestroyed(TEST_NETWORK_1, mockSocket1) }
        verify(mockInterfaceAdvertiser1).destroyNow()
    }

    @Test
    fun testAddService_AllNetworks() {
        val advertiser = MdnsAdvertiser(thread.looper, socketProvider, cb, mockDeps)
        postSync { advertiser.addService(SERVICE_ID_1, ALL_NETWORKS_SERVICE) }

        val socketCbCaptor = ArgumentCaptor.forClass(SocketCallback::class.java)
        verify(socketProvider).requestSocket(eq(ALL_NETWORKS_SERVICE.network),
                socketCbCaptor.capture())

        val socketCb = socketCbCaptor.value
        postSync { socketCb.onSocketCreated(TEST_NETWORK_1, mockSocket1, listOf(TEST_LINKADDR)) }
        postSync { socketCb.onSocketCreated(TEST_NETWORK_2, mockSocket2, listOf(TEST_LINKADDR)) }

        val intAdvCbCaptor1 = ArgumentCaptor.forClass(MdnsInterfaceAdvertiser.Callback::class.java)
        val intAdvCbCaptor2 = ArgumentCaptor.forClass(MdnsInterfaceAdvertiser.Callback::class.java)
        verify(mockDeps).makeAdvertiser(eq(mockSocket1), eq(listOf(TEST_LINKADDR)),
                eq(thread.looper), any(), intAdvCbCaptor1.capture(), eq(TEST_HOSTNAME)
        )
        verify(mockDeps).makeAdvertiser(eq(mockSocket2), eq(listOf(TEST_LINKADDR)),
                eq(thread.looper), any(), intAdvCbCaptor2.capture(), eq(TEST_HOSTNAME)
        )

        doReturn(false).`when`(mockInterfaceAdvertiser1).isProbing(SERVICE_ID_1)
        postSync { intAdvCbCaptor1.value.onRegisterServiceSucceeded(
                mockInterfaceAdvertiser1, SERVICE_ID_1) }

        // Need both advertisers to finish probing and call onRegisterServiceSucceeded
        verify(cb, never()).onRegisterServiceSucceeded(anyInt(), any())
        doReturn(false).`when`(mockInterfaceAdvertiser2).isProbing(SERVICE_ID_1)
        postSync { intAdvCbCaptor2.value.onRegisterServiceSucceeded(
                mockInterfaceAdvertiser2, SERVICE_ID_1) }
        verify(cb).onRegisterServiceSucceeded(eq(SERVICE_ID_1),
                argThat { it.matches(ALL_NETWORKS_SERVICE) })

        // Unregister the service
        postSync { advertiser.removeService(SERVICE_ID_1) }
        verify(mockInterfaceAdvertiser1).removeService(SERVICE_ID_1)
        verify(mockInterfaceAdvertiser2).removeService(SERVICE_ID_1)

        // Interface advertisers call onDestroyed after sending exit announcements
        postSync { intAdvCbCaptor1.value.onDestroyed(mockSocket1) }
        verify(socketProvider, never()).unrequestSocket(any())
        postSync { intAdvCbCaptor2.value.onDestroyed(mockSocket2) }
        verify(socketProvider).unrequestSocket(socketCb)
    }

    @Test
    fun testAddService_Conflicts() {
        val advertiser = MdnsAdvertiser(thread.looper, socketProvider, cb, mockDeps)
        postSync { advertiser.addService(SERVICE_ID_1, SERVICE_1) }

        val oneNetSocketCbCaptor = ArgumentCaptor.forClass(SocketCallback::class.java)
        verify(socketProvider).requestSocket(eq(TEST_NETWORK_1), oneNetSocketCbCaptor.capture())
        val oneNetSocketCb = oneNetSocketCbCaptor.value

        // Register a service with the same name on all networks (name conflict)
        postSync { advertiser.addService(SERVICE_ID_2, ALL_NETWORKS_SERVICE) }
        val allNetSocketCbCaptor = ArgumentCaptor.forClass(SocketCallback::class.java)
        verify(socketProvider).requestSocket(eq(null), allNetSocketCbCaptor.capture())
        val allNetSocketCb = allNetSocketCbCaptor.value

        // Callbacks for matching network and all networks both get the socket
        postSync {
            oneNetSocketCb.onSocketCreated(TEST_NETWORK_1, mockSocket1, listOf(TEST_LINKADDR))
            allNetSocketCb.onSocketCreated(TEST_NETWORK_1, mockSocket1, listOf(TEST_LINKADDR))
        }

        val expectedRenamed = NsdServiceInfo(
                "${ALL_NETWORKS_SERVICE.serviceName} (2)", ALL_NETWORKS_SERVICE.serviceType).apply {
            port = ALL_NETWORKS_SERVICE.port
            host = ALL_NETWORKS_SERVICE.host
            network = ALL_NETWORKS_SERVICE.network
        }

        val intAdvCbCaptor = ArgumentCaptor.forClass(MdnsInterfaceAdvertiser.Callback::class.java)
        verify(mockDeps).makeAdvertiser(eq(mockSocket1), eq(listOf(TEST_LINKADDR)),
                eq(thread.looper), any(), intAdvCbCaptor.capture(), eq(TEST_HOSTNAME)
        )
        verify(mockInterfaceAdvertiser1).addService(eq(SERVICE_ID_1),
                argThat { it.matches(SERVICE_1) })
        verify(mockInterfaceAdvertiser1).addService(eq(SERVICE_ID_2),
                argThat { it.matches(expectedRenamed) })

        doReturn(false).`when`(mockInterfaceAdvertiser1).isProbing(SERVICE_ID_1)
        postSync { intAdvCbCaptor.value.onRegisterServiceSucceeded(
                mockInterfaceAdvertiser1, SERVICE_ID_1) }
        verify(cb).onRegisterServiceSucceeded(eq(SERVICE_ID_1), argThat { it.matches(SERVICE_1) })

        doReturn(false).`when`(mockInterfaceAdvertiser1).isProbing(SERVICE_ID_2)
        postSync { intAdvCbCaptor.value.onRegisterServiceSucceeded(
                mockInterfaceAdvertiser1, SERVICE_ID_2) }
        verify(cb).onRegisterServiceSucceeded(eq(SERVICE_ID_2),
                argThat { it.matches(expectedRenamed) })

        postSync { oneNetSocketCb.onInterfaceDestroyed(TEST_NETWORK_1, mockSocket1) }
        postSync { allNetSocketCb.onInterfaceDestroyed(TEST_NETWORK_1, mockSocket1) }

        // destroyNow can be called multiple times
        verify(mockInterfaceAdvertiser1, atLeastOnce()).destroyNow()
    }

    @Test
    fun testRemoveService_whenAllServiceRemoved_thenUpdateHostName() {
        val advertiser = MdnsAdvertiser(thread.looper, socketProvider, cb, mockDeps)
        verify(mockDeps, times(1)).generateHostname()
        postSync { advertiser.addService(SERVICE_ID_1, SERVICE_1) }
        postSync { advertiser.removeService(SERVICE_ID_1) }
        verify(mockDeps, times(2)).generateHostname()
    }

    private fun postSync(r: () -> Unit) {
        handler.post(r)
        handler.waitForIdle(TIMEOUT_MS)
    }
}

// NsdServiceInfo does not implement equals; this is useful to use in argument matchers
private fun NsdServiceInfo.matches(other: NsdServiceInfo): Boolean {
    return Objects.equals(serviceName, other.serviceName) &&
            Objects.equals(serviceType, other.serviceType) &&
            Objects.equals(attributes, other.attributes) &&
            Objects.equals(host, other.host) &&
            port == other.port &&
            Objects.equals(network, other.network)
}

/*
 * Copyright (C) 2025 The Android Open Source Project
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

package android.net.cts

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.Manifest.permission.NETWORK_SETTINGS
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.RES_ID_MATCH_ALL_RESERVATIONS
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_TEST
import android.net.NetworkProvider
import android.net.NetworkRequest
import android.net.NetworkScore
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.RecorderCallback.CallbackEntry.Reserved
import com.android.testutils.RecorderCallback.CallbackEntry.Unavailable
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkOfferCallback
import com.android.testutils.runAsShell
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "NetworkReservationTest"

private val NETWORK_SCORE = NetworkScore.Builder().build()
private val ETHERNET_CAPS = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_CONGESTED)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private val BLANKET_CAPS = NetworkCapabilities(ETHERNET_CAPS).apply {
    reservationId = RES_ID_MATCH_ALL_RESERVATIONS
}
private val ETHERNET_REQUEST = NetworkRequest.Builder()
        .addTransportType(TRANSPORT_ETHERNET)
        .addTransportType(TRANSPORT_TEST)
        .removeCapability(NET_CAPABILITY_TRUSTED)
        .build()
private const val TIMEOUT_MS = 5_000L
private const val NO_CB_TIMEOUT_MS = 200L

// TODO: integrate with CSNetworkReservationTest and move to common tests.
@ConnectivityModuleTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class NetworkReservationTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val handlerThread = HandlerThread("$TAG handler thread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val provider = NetworkProvider(context, handlerThread.looper, TAG)

    @Before
    fun setUp() {
        runAsShell(NETWORK_SETTINGS) {
            cm.registerNetworkProvider(provider)
        }
    }

    @After
    fun tearDown() {
        runAsShell(NETWORK_SETTINGS) {
            // unregisterNetworkProvider unregisters all associated NetworkOffers.
            cm.unregisterNetworkProvider(provider)
        }
        handlerThread.quitSafely()
        handlerThread.join()
    }

    fun NetworkCapabilities.copyWithReservationId(resId: Int) = NetworkCapabilities(this).also {
        it.reservationId = resId
    }

    @Test
    fun testReserveNetwork() {
        // register blanket offer
        val blanketOffer = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        runAsShell(MANAGE_TEST_NETWORKS) {
            provider.registerNetworkOffer(NETWORK_SCORE, BLANKET_CAPS, handler::post, blanketOffer)
        }

        val cb = TestableNetworkCallback()
        cm.reserveNetwork(ETHERNET_REQUEST, handler, cb)

        // validate the reservation matches the blanket offer.
        val reservationReq = blanketOffer.expectOnNetworkNeeded(BLANKET_CAPS).request
        val reservationId = reservationReq.networkCapabilities.reservationId

        // bring up reserved reservation offer
        val reservedCaps = ETHERNET_CAPS.copyWithReservationId(reservationId)
        val reservedOffer = TestableNetworkOfferCallback(TIMEOUT_MS, NO_CB_TIMEOUT_MS)
        runAsShell(MANAGE_TEST_NETWORKS) {
            provider.registerNetworkOffer(NETWORK_SCORE, reservedCaps, handler::post, reservedOffer)
        }

        // validate onReserved was sent to the app
        val appObservedCaps = cb.expect<Reserved>().caps
        assertEquals(reservedCaps, appObservedCaps)

        // validate the reservation matches the reserved offer.
        reservedOffer.expectOnNetworkNeeded(reservedCaps)

        // reserved offer goes away
        provider.unregisterNetworkOffer(reservedOffer)
        cb.expect<Unavailable>()
    }
}

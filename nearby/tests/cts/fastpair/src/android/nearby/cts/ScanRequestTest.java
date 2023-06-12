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

package android.nearby.cts;

import static android.nearby.PresenceCredential.IDENTITY_TYPE_PRIVATE;
import static android.nearby.ScanRequest.SCAN_MODE_BALANCED;
import static android.nearby.ScanRequest.SCAN_MODE_LOW_LATENCY;
import static android.nearby.ScanRequest.SCAN_MODE_LOW_POWER;
import static android.nearby.ScanRequest.SCAN_MODE_NO_POWER;
import static android.nearby.ScanRequest.SCAN_TYPE_FAST_PAIR;
import static android.nearby.ScanRequest.SCAN_TYPE_NEARBY_PRESENCE;

import static com.google.common.truth.Truth.assertThat;

import android.nearby.PresenceScanFilter;
import android.nearby.PublicCredential;
import android.nearby.ScanRequest;
import android.os.Build;
import android.os.WorkSource;

import androidx.annotation.RequiresApi;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class ScanRequestTest {

    private static final int UID = 1001;
    private static final String APP_NAME = "android.nearby.tests";
    private static final int RSSI = -40;

    @Test
    public void testScanType() {
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                .build();

        assertThat(request.getScanType()).isEqualTo(SCAN_TYPE_NEARBY_PRESENCE);
    }

    // Valid scan type must be set to one of ScanRequest#SCAN_TYPE_
    @Test(expected = IllegalStateException.class)
    public void testScanType_notSet_throwsException() {
        new ScanRequest.Builder().setScanMode(SCAN_MODE_BALANCED).build();
    }

    /** Verify setting work source with null value in the scan request is allowed */
    @Test
    public void testSetWorkSource_nullValue() {
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_FAST_PAIR)
                .setWorkSource(null)
                .build();

        // Null work source is allowed.
        assertThat(request.getWorkSource().isEmpty()).isTrue();
    }

    @Test
    public void testisEnableBle_defaultTrue() {
        ScanRequest request = new ScanRequest.Builder()
                .setScanType(SCAN_TYPE_FAST_PAIR)
                .build();

        assertThat(request.isBleEnabled()).isTrue();
    }

    @Test
    public void testScanFilter() {
        ScanRequest request = new ScanRequest.Builder().setScanType(
                SCAN_TYPE_NEARBY_PRESENCE).addScanFilter(getPresenceScanFilter()).build();

        assertThat(request.getScanFilters()).isNotEmpty();
        assertThat(request.getScanFilters().get(0).getMaxPathLoss()).isEqualTo(RSSI);
    }

    private static PresenceScanFilter getPresenceScanFilter() {
        final byte[] secretId = new byte[]{1, 2, 3, 4};
        final byte[] authenticityKey = new byte[]{0, 1, 1, 1};
        final byte[] publicKey = new byte[]{1, 1, 2, 2};
        final byte[] encryptedMetadata = new byte[]{1, 2, 3, 4, 5};
        final byte[] metadataEncryptionKeyTag = new byte[]{1, 1, 3, 4, 5};

        PublicCredential credential = new PublicCredential.Builder(
                secretId, authenticityKey, publicKey, encryptedMetadata, metadataEncryptionKeyTag)
                .setIdentityType(IDENTITY_TYPE_PRIVATE)
                .build();

        final int action = 123;
        return new PresenceScanFilter.Builder()
                .addCredential(credential)
                .setMaxPathLoss(RSSI)
                .addPresenceAction(action)
                .build();
    }

    private static WorkSource getWorkSource() {
        return new WorkSource(UID, APP_NAME);
    }
}

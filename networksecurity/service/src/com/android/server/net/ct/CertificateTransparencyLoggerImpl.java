/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.net.ct;

import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_DEVICE_OFFLINE;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_DOWNLOAD_CANNOT_RESUME;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_HTTP_ERROR;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_NO_DISK_SPACE;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_PUBLIC_KEY_NOT_FOUND;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_SIGNATURE_INVALID;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_SIGNATURE_NOT_FOUND;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_SIGNATURE_VERIFICATION;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_TOO_MANY_REDIRECTS;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_UNKNOWN;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_VERSION_ALREADY_EXISTS;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__PENDING_WAITING_FOR_WIFI;

import android.app.DownloadManager;

/** Implementation for logging to statsd for Certificate Transparency. */
class CertificateTransparencyLoggerImpl implements CertificateTransparencyLogger {

    private final DataStore mDataStore;

    CertificateTransparencyLoggerImpl(DataStore dataStore) {
        mDataStore = dataStore;
    }

    @Override
    public void logCTLogListUpdateStateChangedEvent(LogListUpdateStatus updateStatus) {
        int updateState =
                updateStatus
                        .downloadStatus()
                        .map(s -> downloadStatusToFailureReason(s))
                        .orElseGet(() -> localEnumToStatsLogEnum(updateStatus.state()));
        int failureCount =
                mDataStore.getPropertyInt(
                        Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0);

        logCTLogListUpdateStateChangedEvent(
                updateState,
                failureCount,
                updateStatus.httpErrorStatusCode(),
                updateStatus.signature());
    }

    private void logCTLogListUpdateStateChangedEvent(
            int updateState, int failureCount, int httpErrorStatusCode, String signature) {
        updateFailureCount();

        CertificateTransparencyStatsLog.write(
                CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED,
                updateState,
                failureCount,
                httpErrorStatusCode,
                signature,
                /* logListTimestampMs= */ 0);
    }

    /**
     * Updates the data store with the current number of consecutive log list update failures.
     */
    private void updateFailureCount() {
        int failure_count =
                mDataStore.getPropertyInt(
                        Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0);
        int new_failure_count = failure_count + 1;

        mDataStore.setPropertyInt(Config.LOG_LIST_UPDATE_FAILURE_COUNT, new_failure_count);
        mDataStore.store();
    }

    /** Converts DownloadStatus reason into failure reason to log. */
    private int downloadStatusToFailureReason(int downloadStatusReason) {
        switch (downloadStatusReason) {
            case DownloadManager.PAUSED_WAITING_TO_RETRY:
            case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_DEVICE_OFFLINE;
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_HTTP_ERROR;
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_TOO_MANY_REDIRECTS;
            case DownloadManager.ERROR_CANNOT_RESUME:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_DOWNLOAD_CANNOT_RESUME;
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_NO_DISK_SPACE;
            case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__PENDING_WAITING_FOR_WIFI;
            default:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_UNKNOWN;
        }
    }

    /** Converts the local enum to the corresponding auto-generated one used by CTStatsLog. */
    private int localEnumToStatsLogEnum(CTLogListUpdateState updateState) {
        switch (updateState) {
            case HTTP_ERROR:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_HTTP_ERROR;
            case PUBLIC_KEY_NOT_FOUND:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_PUBLIC_KEY_NOT_FOUND;
            case SIGNATURE_INVALID:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_SIGNATURE_INVALID;
            case SIGNATURE_NOT_FOUND:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_SIGNATURE_NOT_FOUND;
            case SIGNATURE_VERIFICATION_FAILED:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_SIGNATURE_VERIFICATION;
            case VERSION_ALREADY_EXISTS:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_VERSION_ALREADY_EXISTS;
            case UNKNOWN_STATE:
            default:
                return CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_STATE_CHANGED__UPDATE_STATUS__FAILURE_UNKNOWN;
        }
    }
}

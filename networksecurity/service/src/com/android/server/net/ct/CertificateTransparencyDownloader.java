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

import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_HTTP_ERROR;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_NOT_FOUND;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_VERIFICATION;
import static com.android.server.net.ct.CertificateTransparencyStatsLog.CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_VERSION_ALREADY_EXISTS;

import android.annotation.RequiresApi;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.server.net.ct.DownloadHelper.DownloadStatus;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.List;

/** Helper class to download certificate transparency log files. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CertificateTransparencyDownloader extends BroadcastReceiver {

    private static final String TAG = "CertificateTransparencyDownloader";

    private final Context mContext;
    private final DataStore mDataStore;
    private final DownloadHelper mDownloadHelper;
    private final SignatureVerifier mSignatureVerifier;
    private final CertificateTransparencyLogger mLogger;

    private final List<CompatibilityVersion> mCompatVersions = new ArrayList<>();

    private boolean started = false;

    CertificateTransparencyDownloader(
            Context context,
            DataStore dataStore,
            DownloadHelper downloadHelper,
            SignatureVerifier signatureVerifier,
            CertificateTransparencyLogger logger) {
        mContext = context;
        mSignatureVerifier = signatureVerifier;
        mDataStore = dataStore;
        mDownloadHelper = downloadHelper;
        mLogger = logger;
    }

    void addCompatibilityVersion(CompatibilityVersion compatVersion) {
        mCompatVersions.add(compatVersion);
    }

    void start() {
        if (started) {
            return;
        }
        mContext.registerReceiver(
                this,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED);
        mDataStore.load();
        started = true;

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyDownloader started.");
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        mContext.unregisterReceiver(this);
        mDataStore.delete();
        started = false;

        if (Config.DEBUG) {
            Log.d(TAG, "CertificateTransparencyDownloader stopped.");
        }
    }

    long startPublicKeyDownload() {
        long downloadId = download(Config.URL_PUBLIC_KEY);
        if (downloadId != -1) {
            mDataStore.setPropertyLong(Config.PUBLIC_KEY_DOWNLOAD_ID, downloadId);
            mDataStore.store();
        }
        return downloadId;
    }

    private long startMetadataDownload(CompatibilityVersion compatVersion) {
        long downloadId = download(compatVersion.getMetadataUrl());
        if (downloadId != -1) {
            mDataStore.setPropertyLong(compatVersion.getMetadataPropertyName(), downloadId);
            mDataStore.store();
        }
        return downloadId;
    }

    @VisibleForTesting
    void startMetadataDownload() {
        for (CompatibilityVersion compatVersion : mCompatVersions) {
            if (startMetadataDownload(compatVersion) == -1) {
                Log.e(TAG, "Metadata download not started for " + compatVersion.getCompatVersion());
            } else if (Config.DEBUG) {
                Log.d(TAG, "Metadata download started for " + compatVersion.getCompatVersion());
            }
        }
    }

    @VisibleForTesting
    long startContentDownload(CompatibilityVersion compatVersion) {
        long downloadId = download(compatVersion.getContentUrl());
        if (downloadId != -1) {
            mDataStore.setPropertyLong(compatVersion.getContentPropertyName(), downloadId);
            mDataStore.store();
        }
        return downloadId;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            Log.w(TAG, "Received unexpected broadcast with action " + action);
            return;
        }

        long completedId =
                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, /* defaultValue= */ -1);
        if (completedId == -1) {
            Log.e(TAG, "Invalid completed download Id");
            return;
        }

        if (getPublicKeyDownloadId() == completedId) {
            handlePublicKeyDownloadCompleted(completedId);
            return;
        }

        for (CompatibilityVersion compatVersion : mCompatVersions) {
            if (getMetadataDownloadId(compatVersion) == completedId) {
                handleMetadataDownloadCompleted(compatVersion, completedId);
                return;
            }

            if (getContentDownloadId(compatVersion) == completedId) {
                handleContentDownloadCompleted(compatVersion, completedId);
                return;
            }
        }

        Log.i(TAG, "Download id " + completedId + " is not recognized.");
    }

    private void handlePublicKeyDownloadCompleted(long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }

        Uri publicKeyUri = getPublicKeyDownloadUri();
        if (publicKeyUri == null) {
            Log.e(TAG, "Invalid public key URI");
            return;
        }

        try {
            mSignatureVerifier.setPublicKeyFrom(publicKeyUri);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            Log.e(TAG, "Error setting the public Key", e);
            return;
        }

        startMetadataDownload();
    }

    private void handleMetadataDownloadCompleted(
            CompatibilityVersion compatVersion, long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }
        if (startContentDownload(compatVersion) == -1) {
            Log.e(TAG, "Content download failed for" + compatVersion.getCompatVersion());
        } else if (Config.DEBUG) {
            Log.d(TAG, "Content download started for" + compatVersion.getCompatVersion());
        }
    }

    private void handleContentDownloadCompleted(
            CompatibilityVersion compatVersion, long downloadId) {
        DownloadStatus status = mDownloadHelper.getDownloadStatus(downloadId);
        if (!status.isSuccessful()) {
            handleDownloadFailed(status);
            return;
        }

        Uri contentUri = getContentDownloadUri(compatVersion);
        Uri metadataUri = getMetadataDownloadUri(compatVersion);
        if (contentUri == null || metadataUri == null) {
            Log.e(TAG, "Invalid URIs");
            return;
        }

        boolean success = false;
        int failureReason = -1;

        try {
            success = mSignatureVerifier.verify(contentUri, metadataUri);
        } catch (MissingPublicKeyException e) {
            if (updateFailureCount()) {
                failureReason = CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_NOT_FOUND;
            }
        } catch (InvalidKeyException e) {
            if (updateFailureCount()) {
                failureReason = CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_VERIFICATION;
            }
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "Could not verify new log list", e);
        }

        if (!success) {
            Log.w(TAG, "Log list did not pass verification");

            // Avoid logging failure twice
            if (failureReason == -1 && updateFailureCount()) {
                failureReason = CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_SIGNATURE_VERIFICATION;
            }

            if (failureReason != -1) {
                mLogger.logCTLogListUpdateFailedEvent(
                        failureReason,
                        mDataStore.getPropertyInt(
                                Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0));
            }
            return;
        }

        try (InputStream inputStream = mContext.getContentResolver().openInputStream(contentUri)) {
            success = compatVersion.install(inputStream);
        } catch (IOException e) {
            Log.e(TAG, "Could not install new content", e);
            return;
        }

        if (success) {
            // Reset the number of consecutive log list failure updates back to zero.
            mDataStore.setPropertyInt(Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* value= */ 0);
            mDataStore.store();
        } else {
            if (updateFailureCount()) {
                mLogger.logCTLogListUpdateFailedEvent(
                        CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_VERSION_ALREADY_EXISTS,
                        mDataStore.getPropertyInt(
                                Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0));
            }
        }
    }

    private void handleDownloadFailed(DownloadStatus status) {
        Log.e(TAG, "Download failed with " + status);

        if (updateFailureCount()) {
            int failureCount =
                    mDataStore.getPropertyInt(
                            Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0);

            if (status.isHttpError()) {
                mLogger.logCTLogListUpdateFailedEvent(
                        CERTIFICATE_TRANSPARENCY_LOG_LIST_UPDATE_FAILED__FAILURE_REASON__FAILURE_HTTP_ERROR,
                        failureCount,
                        status.reason());
            } else {
                // TODO(b/384935059): handle blocked domain logging
                mLogger.logCTLogListUpdateFailedEventWithDownloadStatus(
                        status.reason(), failureCount);
            }
        }
    }

    /**
     * Updates the data store with the current number of consecutive log list update failures.
     *
     * @return whether the failure count exceeds the threshold and should be logged.
     */
    private boolean updateFailureCount() {
        int failure_count =
                mDataStore.getPropertyInt(
                        Config.LOG_LIST_UPDATE_FAILURE_COUNT, /* defaultValue= */ 0);
        int new_failure_count = failure_count + 1;

        mDataStore.setPropertyInt(Config.LOG_LIST_UPDATE_FAILURE_COUNT, new_failure_count);
        mDataStore.store();

        boolean shouldReport = new_failure_count >= Config.LOG_LIST_UPDATE_FAILURE_THRESHOLD;
        if (shouldReport) {
            Log.d(TAG, "Log list update failure count exceeds threshold: " + new_failure_count);
        }
        return shouldReport;
    }

    private long download(String url) {
        try {
            return mDownloadHelper.startDownload(url);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Download request failed", e);
            return -1;
        }
    }

    @VisibleForTesting
    long getPublicKeyDownloadId() {
        return mDataStore.getPropertyLong(Config.PUBLIC_KEY_DOWNLOAD_ID, /* defaultValue= */ -1);
    }

    @VisibleForTesting
    long getMetadataDownloadId(CompatibilityVersion compatVersion) {
        return mDataStore.getPropertyLong(
                compatVersion.getMetadataPropertyName(), /* defaultValue */ -1);
    }

    @VisibleForTesting
    long getContentDownloadId(CompatibilityVersion compatVersion) {
        return mDataStore.getPropertyLong(
                compatVersion.getContentPropertyName(), /* defaultValue= */ -1);
    }

    @VisibleForTesting
    boolean hasPublicKeyDownloadId() {
        return getPublicKeyDownloadId() != -1;
    }

    @VisibleForTesting
    boolean hasMetadataDownloadId() {
        return mCompatVersions.stream()
                .map(this::getMetadataDownloadId)
                .anyMatch(downloadId -> downloadId != -1);
    }

    @VisibleForTesting
    boolean hasContentDownloadId() {
        return mCompatVersions.stream()
                .map(this::getContentDownloadId)
                .anyMatch(downloadId -> downloadId != -1);
    }

    private Uri getPublicKeyDownloadUri() {
        return mDownloadHelper.getUri(getPublicKeyDownloadId());
    }

    private Uri getMetadataDownloadUri(CompatibilityVersion compatVersion) {
        return mDownloadHelper.getUri(getMetadataDownloadId(compatVersion));
    }

    private Uri getContentDownloadUri(CompatibilityVersion compatVersion) {
        return mDownloadHelper.getUri(getContentDownloadId(compatVersion));
    }
}

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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;

/** Tests for the {@link CertificateTransparencyDownloader}. */
@RunWith(JUnit4.class)
public class CertificateTransparencyDownloaderTest {

    @Mock private DownloadHelper mDownloadHelper;
    @Mock private CertificateTransparencyInstaller mCertificateTransparencyInstaller;

    private Context mContext;
    private File mTempFile;
    private DataStore mDataStore;
    private CertificateTransparencyDownloader mCertificateTransparencyDownloader;

    @Before
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mTempFile = File.createTempFile("datastore-test", ".properties");
        mDataStore = new DataStore(mTempFile);
        mDataStore.load();

        mCertificateTransparencyDownloader =
                new CertificateTransparencyDownloader(
                        mContext, mDataStore, mDownloadHelper, mCertificateTransparencyInstaller);
    }

    @After
    public void tearDown() {
        mTempFile.delete();
    }

    @Test
    public void testDownloader_startMetadataDownload() {
        String metadataUrl = "http://test-metadata.org";
        long downloadId = 666;
        when(mDownloadHelper.startDownload(metadataUrl)).thenReturn(downloadId);

        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(downloadId)).isFalse();
        mCertificateTransparencyDownloader.startMetadataDownload(metadataUrl);
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_startContentDownload() {
        String contentUrl = "http://test-content.org";
        long downloadId = 666;
        when(mDownloadHelper.startDownload(contentUrl)).thenReturn(downloadId);

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(downloadId)).isFalse();
        mCertificateTransparencyDownloader.startContentDownload(contentUrl);
        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_handleMetadataCompleteSuccessful() {
        long metadataId = 123;
        mDataStore.setPropertyLong(Config.METADATA_URL_KEY, metadataId);
        when(mDownloadHelper.isSuccessful(metadataId)).thenReturn(true);

        long contentId = 666;
        String contentUrl = "http://test-content.org";
        mDataStore.setProperty(Config.CONTENT_URL_PENDING, contentUrl);
        when(mDownloadHelper.startDownload(contentUrl)).thenReturn(contentId);

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(metadataId));

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(contentId)).isTrue();
    }

    @Test
    public void testDownloader_handleMetadataCompleteFailed() {
        long metadataId = 123;
        mDataStore.setPropertyLong(Config.METADATA_URL_KEY, metadataId);
        when(mDownloadHelper.isSuccessful(metadataId)).thenReturn(false);

        String contentUrl = "http://test-content.org";
        mDataStore.setProperty(Config.CONTENT_URL_PENDING, contentUrl);

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(metadataId));

        verify(mDownloadHelper, never()).startDownload(contentUrl);
    }

    @Test
    public void testDownloader_handleContentCompleteInstallSuccessful() throws IOException {
        String version = "666";
        mDataStore.setProperty(Config.VERSION_PENDING, version);

        long metadataId = 123;
        mDataStore.setPropertyLong(Config.METADATA_URL_KEY, metadataId);
        Uri metadataUri = Uri.fromFile(File.createTempFile("log_list-metadata", "txt"));
        mDataStore.setProperty(Config.METADATA_URL_PENDING, metadataUri.toString());
        when(mDownloadHelper.getUri(metadataId)).thenReturn(metadataUri);

        long contentId = 666;
        mDataStore.setPropertyLong(Config.CONTENT_URL_KEY, contentId);
        when(mDownloadHelper.isSuccessful(contentId)).thenReturn(true);
        Uri contentUri = Uri.fromFile(File.createTempFile("log_list", "json"));
        mDataStore.setProperty(Config.CONTENT_URL_PENDING, contentUri.toString());
        when(mDownloadHelper.getUri(contentId)).thenReturn(contentUri);

        when(mCertificateTransparencyInstaller.install(any(), eq(version))).thenReturn(true);

        assertThat(mDataStore.getProperty(Config.VERSION)).isNull();
        assertThat(mDataStore.getProperty(Config.CONTENT_URL)).isNull();
        assertThat(mDataStore.getProperty(Config.METADATA_URL)).isNull();

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        verify(mCertificateTransparencyInstaller, times(1)).install(any(), eq(version));
        assertThat(mDataStore.getProperty(Config.VERSION)).isEqualTo(version);
        assertThat(mDataStore.getProperty(Config.CONTENT_URL)).isEqualTo(contentUri.toString());
        assertThat(mDataStore.getProperty(Config.METADATA_URL)).isEqualTo(metadataUri.toString());
    }

    @Test
    public void testDownloader_handleContentCompleteInstallFails() throws IOException {
        String version = "666";
        mDataStore.setProperty(Config.VERSION_PENDING, version);

        long metadataId = 123;
        mDataStore.setPropertyLong(Config.METADATA_URL_KEY, metadataId);
        Uri metadataUri = Uri.fromFile(File.createTempFile("log_list-metadata", "txt"));
        mDataStore.setProperty(Config.METADATA_URL_PENDING, metadataUri.toString());
        when(mDownloadHelper.getUri(metadataId)).thenReturn(metadataUri);

        long contentId = 666;
        mDataStore.setPropertyLong(Config.CONTENT_URL_KEY, contentId);
        when(mDownloadHelper.isSuccessful(contentId)).thenReturn(true);
        Uri contentUri = Uri.fromFile(File.createTempFile("log_list", "json"));
        mDataStore.setProperty(Config.CONTENT_URL_PENDING, contentUri.toString());
        when(mDownloadHelper.getUri(contentId)).thenReturn(contentUri);

        when(mCertificateTransparencyInstaller.install(any(), eq(version))).thenReturn(false);

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        assertThat(mDataStore.getProperty(Config.VERSION)).isNull();
        assertThat(mDataStore.getProperty(Config.CONTENT_URL)).isNull();
        assertThat(mDataStore.getProperty(Config.METADATA_URL)).isNull();
    }

    private Intent makeDownloadCompleteIntent(long downloadId) {
        return new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
    }
}
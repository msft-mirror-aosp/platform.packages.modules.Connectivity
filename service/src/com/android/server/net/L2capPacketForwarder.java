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

package com.android.server.net;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Forwards packets from a BluetoothSocket of type L2CAP to a tun fd and vice versa.
 *
 * The forwarding logic operates on raw IP packets and there are no ethernet headers.
 * Therefore, L3 MTU = L2 MTU.
 */
public class L2capPacketForwarder {
    private static final String TAG = "L2capPacketForwarder";

    // DCT specifies an MTU of 1500.
    // TODO: Set /proc/sys/net/ipv6/conf/${iface}/mtu to 1280 and the link MTU to 1528 to accept
    // slightly larger packets on ingress (i.e. packets passing through a NAT64 gateway).
    // MTU determines the value of the read buffers, so use the larger of the two.
    @VisibleForTesting
    public static final int MTU = 1528;
    private final Handler mHandler;
    private final IReadWriteFd mTunFd;
    private final IReadWriteFd mL2capFd;
    private final L2capThread mIngressThread;
    private final L2capThread mEgressThread;
    private final ICallback mCallback;

    public interface ICallback {
        /** Called when an error is encountered; should tear down forwarding. */
        void onError();
    }

    private interface IReadWriteFd {
        /**
         * Read up to len bytes into bytes[off] and return bytes actually read.
         *
         * bytes[] must be of size >= off + len.
         */
        int read(byte[] bytes, int off, int len) throws IOException;
        /**
         * Write len bytes starting from bytes[off]
         *
         * bytes[] must be of size >= off + len.
         */
        void write(byte[] bytes, int off, int len) throws IOException;
        /** Disallow further receptions, shutdown(fd, SHUT_RD) */
        void shutdownRead();
        /** Disallow further transmissions, shutdown(fd, SHUT_WR) */
        void shutdownWrite();
        /** Close the fd */
        void close();
    }

    @VisibleForTesting
    public static class BluetoothSocketWrapper implements IReadWriteFd {
        private final BluetoothSocket mSocket;
        private final InputStream mInputStream;
        private final OutputStream mOutputStream;

        public BluetoothSocketWrapper(BluetoothSocket socket) {
            // TODO: assert that MTU can fit within Bluetooth L2CAP SDU (maximum size of an L2CAP
            // packet). The L2CAP SDU is 65535 by default, but can be less when using hardware
            // offload.
            mSocket = socket;
            try {
                mInputStream = socket.getInputStream();
                mOutputStream = socket.getOutputStream();
            } catch (IOException e) {
                // Per the API docs, this should not actually be possible.
                Log.wtf(TAG, "Failed to get Input/OutputStream", e);
                // Fail hard.
                throw new IllegalStateException("Failed to get Input/OutputStream");
            }
        }

        /** Read from the BluetoothSocket. */
        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            // Note: EINTR is handled internally and automatically triggers a retry loop.
            int bytesRead = mInputStream.read(bytes, off, len);
            if (bytesRead > MTU) {
                // Don't try to recover, just trigger network teardown. This might indicate a bug in
                // the Bluetooth stack.
                throw new IOException("Packet exceeds MTU");
            }
            return bytesRead;
        }

        /** Write to the BluetoothSocket. */
        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            // Note: EINTR is handled internally and automatically triggers a retry loop.
            mOutputStream.write(bytes, off, len);
        }

        @Override
        public void shutdownRead() {
            // BluetoothSocket does not expose methods to shutdown read / write individually;
            // however, BluetoothSocket#close() shuts down both read and write and is safe to be
            // called multiple times from any thread.
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "shutdownRead: Failed to close BluetoothSocket", e);
            }
        }

        @Override
        public void shutdownWrite() {
            // BluetoothSocket does not expose methods to shutdown read / write individually;
            // however, BluetoothSocket#close() shuts down both read and write and is safe to be
            // called multiple times from any thread.
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "shutdownWrite: Failed to close BluetoothSocket", e);
            }
        }

        @Override
        public void close() {
            // BluetoothSocket#close() is safe to be called multiple times.
            try {
                mSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "close: Failed to close BluetoothSocket", e);
            }
        }
    }

    @VisibleForTesting
    public static class FdWrapper implements IReadWriteFd {
        private final ParcelFileDescriptor mFd;

        public FdWrapper(ParcelFileDescriptor fd) {
            mFd = fd;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            try {
                // Note: EINTR is handled internally and automatically triggers a retry loop.
                return Os.read(mFd.getFileDescriptor(), bytes, off, len);
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
        }

        /**
         * Write to BluetoothSocket.
         */
        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            try {
                // Note: EINTR is handled internally and automatically triggers a retry loop.
                Os.write(mFd.getFileDescriptor(), bytes, off, len);
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void shutdownRead() {
            try {
                Os.shutdown(mFd.getFileDescriptor(), OsConstants.SHUT_RD);
            } catch (ErrnoException e) {
                Log.w(TAG, "shutdownRead: Failed to shutdown(fd, SHUT_RD)", e);
            }
        }

        @Override
        public void shutdownWrite() {
            try {
                Os.shutdown(mFd.getFileDescriptor(), OsConstants.SHUT_WR);
            } catch (ErrnoException e) {
                Log.w(TAG, "shutdownWrite: Failed to shutdown(fd, SHUT_WR)", e);
            }
        }

        @Override
        public void close() {
            try {
                // Safe to call multiple times. Both Os.close(FileDescriptor) and
                // ParcelFileDescriptor#close() offer protection against double-closing an fd.
                mFd.close();
            } catch (IOException e) {
                Log.w(TAG, "close: Failed to close fd", e);
            }
        }
    }

    private class L2capThread extends Thread {
        // Set mBuffer length to MTU + 1 to catch read() overflows.
        private final byte[] mBuffer = new byte[MTU + 1];
        private volatile boolean mIsRunning = true;

        private final String mLogTag;
        private IReadWriteFd mReadFd;
        private IReadWriteFd mWriteFd;

        L2capThread(String logTag, IReadWriteFd readFd, IReadWriteFd writeFd) {
            mLogTag = logTag;
            mReadFd = readFd;
            mWriteFd = writeFd;
        }

        private void postOnError() {
            mHandler.post(() -> {
                // All callbacks must be called on handler thread.
                mCallback.onError();
            });
        }

        @Override
        public void run() {
            while (mIsRunning) {
                try {
                    final int readBytes = mReadFd.read(mBuffer, 0 /*off*/, mBuffer.length);
                    // No bytes to write, continue.
                    if (readBytes <= 0) {
                        Log.w(mLogTag, "Zero-byte read encountered: " + readBytes);
                        continue;
                    }

                    // If the packet exceeds MTU, drop it.
                    // Note that a large read on BluetoothSocket throws an IOException to tear down
                    // the network.
                    if (readBytes > MTU) continue;

                    mWriteFd.write(mBuffer, 0 /*off*/, readBytes);
                } catch (IOException e) {
                    Log.e(mLogTag, "L2capThread exception", e);
                    // Tear down the network on any error.
                    mIsRunning = false;
                    // Notify provider that forwarding has stopped.
                    postOnError();
                }
            }
        }

        public void tearDown() {
            mIsRunning = false;
            mReadFd.shutdownRead();
            mWriteFd.shutdownWrite();
        }
    }

    public L2capPacketForwarder(Handler handler, ParcelFileDescriptor tunFd, BluetoothSocket socket,
            ICallback cb) {
        this(handler, new FdWrapper(tunFd), new BluetoothSocketWrapper(socket), cb);
    }

    @VisibleForTesting
    L2capPacketForwarder(Handler handler, IReadWriteFd tunFd, IReadWriteFd l2capFd, ICallback cb) {
        mHandler = handler;
        mTunFd = tunFd;
        mL2capFd = l2capFd;
        mCallback = cb;

        mIngressThread = new L2capThread("L2capThread-Ingress", l2capFd, tunFd);
        mEgressThread = new L2capThread("L2capThread-Egress", tunFd, l2capFd);

        mIngressThread.start();
        mEgressThread.start();
    }

    /**
     * Tear down the L2capPacketForwarder.
     *
     * This operation closes both the passed tun fd and BluetoothSocket.
     **/
    public void tearDown() {
        // Destroying both threads first guarantees that both read and write side of FD have been
        // shutdown.
        mIngressThread.tearDown();
        mEgressThread.tearDown();

        // In order to interrupt a blocking read on the BluetoothSocket, the BluetoothSocket must be
        // closed (which triggers shutdown()). This means, the BluetoothSocket must be closed inside
        // L2capPacketForwarder. Tear down the tun fd alongside it for consistency.
        mTunFd.close();
        mL2capFd.close();

        try {
            mIngressThread.join();
        } catch (InterruptedException e) {
            // join() interrupted in tearDown path, do nothing.
        }
        try {
            mEgressThread.join();
        } catch (InterruptedException e) {
            // join() interrupted in tearDown path, do nothing.
        }
    }
}

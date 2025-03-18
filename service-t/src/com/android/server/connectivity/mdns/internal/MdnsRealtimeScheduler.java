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

package com.android.server.connectivity.mdns.internal;

import android.os.Handler;
import android.os.Message;

import androidx.annotation.NonNull;

import com.android.net.module.util.RealtimeScheduler;
import com.android.server.connectivity.mdns.Scheduler;

/**
 * The delay callback for delivering scheduled tasks accurately.
 */
public class MdnsRealtimeScheduler extends RealtimeScheduler implements
        Scheduler {
    private static final String TAG = MdnsRealtimeScheduler.class.getSimpleName();

    public MdnsRealtimeScheduler(@NonNull Handler handler) {
        super(handler);
    }

    public boolean sendDelayedMessage(@NonNull Message message, long delayMs) {
        return super.sendDelayedMessage(message, delayMs);
    }

    public void removeDelayedMessage(int what) {
        super.removeDelayedMessage(what);
    }

    public boolean hasDelayedMessage(int what) {
        return super.hasDelayedMessage(what);
    }

    public boolean postDelayed(@NonNull Runnable runnable, long delayMs) {
        return super.postDelayed(runnable, delayMs);
    }

    public void close() {
        super.close();
    }
}

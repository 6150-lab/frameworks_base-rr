/*
 * Copyright (C) 2025 AxionOS
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
package com.android.server;

import android.content.Context;

import com.android.server.am.*;
import com.android.server.pm.*;
import com.android.server.spoof.RrSpoofManager;
import com.android.server.spoof.IRrSpoofManager;
import com.android.server.wm.WindowManagerService;

public class RrExtServiceFactory {
    private static RrExtServiceFactory sInstance = null;

    private static final Object sLock = new Object();
    
    private static volatile IRrSpoofManager sRrSpoofManager;

    private RrExtServiceFactory(Context context) {
        NtServiceInjector.get().setCtx(context);
    }

    public static synchronized RrExtServiceFactory init(Context context) {
        if (sInstance == null) {
            sInstance = new RrExtServiceFactory(context);
        }
        return sInstance;
    }

    public static RrExtServiceFactory get() {
        if (sInstance == null) {
            throw new IllegalStateException("RrExtServiceFactory not initialized");
        }
        return sInstance;
    }

    public static void injectActivityManagerService(ActivityManagerService ams) {
        NtServiceInjector.get().setActivityManagerService(ams);
    }

    public static void injectWindowManagerService(WindowManagerService wms) {
        NtServiceInjector.get().setWindowManagerService(wms);
    }
    
    public static void injectPackageManagerservice(PackageManagerService pm) {
        NtServiceInjector.get().setPackageManagerService(pm);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getOrCreate(IRrExtServiceFactory.ExtType type) {
        Object instance;
        switch (type) {
            case RR_SPOOF_MANAGER:
                if (sRrSpoofManager == null) {
                    synchronized (sLock) {
                        if (sRrSpoofManager == null) {
                            sRrSpoofManager = new RrSpoofManager();
                        }
                    }
                }
                instance = sRrSpoofManager;
                break;

            default:
                throw new IllegalArgumentException("Unknown ExtType: " + type);
        }

        return (T) type.getClazz().cast(instance);
    }

    public static void systemReady() {
        getSpoofManager().systemReady();
    }
    
    public static void onLateSystemReady() {
    }
    
    public static IRrSpoofManager getSpoofManager() {
        return getOrCreate(IRrExtServiceFactory.ExtType.RR_SPOOF_MANAGER);
    }
}

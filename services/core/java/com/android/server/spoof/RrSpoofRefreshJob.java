/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.spoof;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.android.server.RrExtServiceFactory;

public class RrSpoofRefreshJob extends JobService {
    private static final int JOB_ID = 0x52525350;

    static void schedule(Context context, long intervalMs) {
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(JOB_ID, new ComponentName(context, RrSpoofRefreshJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setRequiresDeviceIdle(true)
                        .setPeriodic(intervalMs)
                        .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final IRrSpoofManager manager = RrExtServiceFactory.getSpoofManager();
        if (!(manager instanceof RrSpoofManager)) return false;
        ((RrSpoofManager) manager).refreshFromJob(() -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}

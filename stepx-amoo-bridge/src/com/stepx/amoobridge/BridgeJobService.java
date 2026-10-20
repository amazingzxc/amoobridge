package com.stepx.amoobridge;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BridgeJobService extends JobService {
    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            JobInfo ji = new JobInfo.Builder(2001, new ComponentName(ctx, BridgeJobService.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(ji);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        try {
            Intent i = new Intent(this, BridgeService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable ignored) {
        }
        jobFinished(p, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return true;
    }
}

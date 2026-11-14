package de.familienzeit.eltern;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Schaut alle 15 Minuten im Hintergrund nach neuen Meldungen. */
public class PollJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(() -> {
            boolean retry = false;
            try {
                Sync.pollAll(this, true);
            } catch (Exception e) {
                retry = true;
            }
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}

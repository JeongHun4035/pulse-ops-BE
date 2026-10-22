package com.pulseops.worker;

import com.pulseops.check.HttpProbe;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class Worker {
    private final WorkerStore store;
    private final HttpProbe probe;
    private final Clock clock;
    public Worker(WorkerStore store, HttpProbe probe, Clock clock) { this.store = store; this.probe = probe; this.clock = clock; }
    public void runOnce() {
        var cutoff = clock.instant();
        int failures = 0;
        for (long id : store.dueIds(cutoff)) {
            try (var claim = store.claim(id, cutoff)) {
                if (claim != null) claim.complete(probe.check(claim.url()), clock.instant());
            } catch (Exception e) {
                failures++;
                LoggerFactory.getLogger(Worker.class).error("Worker failed for site {}", id, e);
            }
        }
        if (failures > 0) throw new IllegalStateException("Worker could not persist " + failures + " checks");
    }
}

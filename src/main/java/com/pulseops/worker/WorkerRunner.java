
package com.pulseops.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class WorkerRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(WorkerRunner.class);

    private final Worker worker;

    public WorkerRunner(Worker worker) {
        this.worker = worker;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("PulseOps monitoring worker started");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                worker.runOnce();
            } catch (Exception e) {
                log.error("Worker execution failed; retrying", e);
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("PulseOps monitoring worker stopped");
    }
}

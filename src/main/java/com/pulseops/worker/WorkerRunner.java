package com.pulseops.worker;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("worker")
public class WorkerRunner implements ApplicationRunner {
    private final Worker worker;
    public WorkerRunner(Worker worker) { this.worker = worker; }
    @Override public void run(ApplicationArguments args) { worker.runOnce(); }
}

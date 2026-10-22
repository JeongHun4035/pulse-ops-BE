package com.pulseops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

@SpringBootApplication
public class PulseOpsApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(PulseOpsApplication.class, args);
        if (context.getEnvironment().matchesProfiles("worker")) {
            System.exit(SpringApplication.exit(context));
        }
    }
    @Bean Clock clock() { return Clock.systemUTC(); }
}

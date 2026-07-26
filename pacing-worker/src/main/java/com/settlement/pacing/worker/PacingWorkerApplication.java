package com.settlement.pacing.worker;

import com.settlement.pacing.worker.config.PacingWorkerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PacingWorkerProperties.class)
public class PacingWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PacingWorkerApplication.class, args);
    }
}

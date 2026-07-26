package com.settlement.pacing.api;

import com.settlement.pacing.api.config.HmacSecurityProperties;
import com.settlement.pacing.api.config.PacingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        PacingProperties.class,
        HmacSecurityProperties.class
})
public class PacingApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(PacingApiApplication.class, args);
    }
}

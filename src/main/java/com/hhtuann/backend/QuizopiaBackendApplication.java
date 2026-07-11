package com.hhtuann.backend;

import com.hhtuann.backend.security.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(SecurityProperties.class)
@EnableScheduling
public class QuizopiaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizopiaBackendApplication.class, args);
    }

}

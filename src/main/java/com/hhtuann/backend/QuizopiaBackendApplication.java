package com.hhtuann.backend;

import com.hhtuann.backend.security.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SecurityProperties.class)
public class QuizopiaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizopiaBackendApplication.class, args);
    }

}

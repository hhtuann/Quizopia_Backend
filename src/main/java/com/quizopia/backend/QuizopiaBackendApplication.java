package com.quizopia.backend;

import com.quizopia.backend.security.config.SecurityProperties;
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

package com.quizopia.backend;

import com.quizopia.backend.account.application.PasswordResetProperties;
import com.quizopia.backend.authentication.application.EmailVerificationProperties;
import com.quizopia.backend.authentication.oauth.OAuthProperties;
import com.quizopia.backend.community.application.CommunityRateLimitProperties;
import com.quizopia.backend.security.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({SecurityProperties.class, EmailVerificationProperties.class, OAuthProperties.class,
        PasswordResetProperties.class, CommunityRateLimitProperties.class,
        com.quizopia.backend.monitoring.application.ActivityIngestProperties.class})
@EnableScheduling
public class QuizopiaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuizopiaBackendApplication.class, args);
    }

}

package com.hhtuann.backend;

import com.hhtuann.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
class QuizopiaBackendApplicationTests {

    @Test
    void contextLoads() {
        // Test rằng application context load được thành công
    }

}

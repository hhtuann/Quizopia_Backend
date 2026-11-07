package com.quizopia.backend.exam;

import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Temporary probe: verifies the user-centric Spring context boots. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
class ContextBootProbeTests {

    @Autowired
    ApplicationContext ctx;

    @Test
    void contextBoots() {
        org.junit.jupiter.api.Assertions.assertNotNull(ctx);
    }
}

package com.quizopia.backend.authentication;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity-M1 concurrency: duplicate username/email registrations racing each
 * other must settle on exactly one 201; the losers receive 409 from the unique
 * lower() indexes (never a 500, never two users).
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class RegistrationRaceConcurrencyTests extends AbstractAuthenticationIntegrationTests {

    private static final int THREADS = 8;

    @Test
    void concurrentDuplicateUsernameYieldsExactlyOneWinner() throws Exception {
        String username = "race-" + UUID.randomUUID().toString().substring(0, 8);
        List<Callable<Integer>> racers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final String email = emailFor(username + "-" + i);
            final String json = studentRegisterJson(
                    username, email, "Passw0rd!", username + " Name", "+84991234567");
            racers.add(() -> register(json).getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(racers);

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(THREADS - 1);
        assertThat(statuses).doesNotContain(500);
    }

    @Test
    void concurrentDuplicateEmailYieldsExactlyOneWinner() throws Exception {
        String email = "race-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        List<Callable<Integer>> racers = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            final String username = "racer-" + UUID.randomUUID().toString().substring(0, 8) + "-" + i;
            final String json = studentRegisterJson(
                    username, email, "Passw0rd!", username + " Name", "+84991234567");
            racers.add(() -> register(json).getResponse().getStatus());
        }

        List<Integer> statuses = runConcurrently(racers);

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(THREADS - 1);
        assertThat(statuses).doesNotContain(500);
    }

    private List<Integer> runConcurrently(List<Callable<Integer>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> racer : racers) {
                futures.add(pool.submit(racer));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String emailFor(String username) {
        return username + "@example.com";
    }
}

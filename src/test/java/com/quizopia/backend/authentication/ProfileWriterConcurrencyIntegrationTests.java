package com.quizopia.backend.authentication;

import com.quizopia.backend.account.application.ProfileService;
import com.quizopia.backend.account.dto.UpdateProfileRequest;
import com.quizopia.backend.authentication.application.CredentialSecurityService;
import com.quizopia.backend.identity.domain.model.*;
import com.quizopia.backend.identity.repository.*;
import com.quizopia.backend.security.encryption.SensitiveDataEncryptor;
import com.quizopia.backend.security.password.PasswordHasher;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.user.application.UserService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class ProfileWriterConcurrencyIntegrationTests {
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired UserRoleRepository userRoles;
    @Autowired UserService admins;
    @Autowired ProfileService profiles;
    @Autowired CredentialSecurityService credentials;
    @Autowired SensitiveDataEncryptor encryptor;
    @Autowired PasswordHasher passwords;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void freshLocksPreserveProfileAndAdminStatusInBothCommitOrders(boolean adminCommitsFirst) throws Exception {
        User admin = seed(UserStatus.ACTIVE);
        User owner = seed(adminCommitsFirst ? UserStatus.PENDING : UserStatus.ACTIVE);
        tx.executeWithoutResult(s -> userRoles.saveAndFlush(new UserRole(users.findById(admin.getId()).orElseThrow(),
                roles.findByCode("SYSTEM_ADMIN").orElseThrow(), null, null)));
        CountDownLatch staleRead = new CountDownLatch(1);
        CountDownLatch firstCommitted = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> second = pool.submit(() -> tx.executeWithoutResult(s -> {
                // Intentionally populate this transaction's first-level cache before the other writer.
                users.findById(owner.getId()).orElseThrow();
                staleRead.countDown();
                await(firstCommitted);
                if (adminCommitsFirst) {
                    profiles.update(owner.getId(), 0, new UpdateProfileRequest("Updated Profile", "+84987654321"));
                } else {
                    admins.disable(admin.getId(), owner.getId());
                }
            }));
            await(staleRead);
            try {
                if (adminCommitsFirst) admins.activate(admin.getId(), owner.getId());
                else profiles.update(owner.getId(), 0, new UpdateProfileRequest("Updated Profile", "+84987654321"));
            } finally { firstCommitted.countDown(); }
            second.get(10, TimeUnit.SECONDS);
        }
        User result = users.findById(owner.getId()).orElseThrow();
        assertThat(result.getDisplayName()).isEqualTo("Updated Profile");
        assertThat(result.getPhoneEncrypted()).doesNotContain("84987654321");
        assertThat(encryptor.decrypt(result.getPhoneEncrypted())).isEqualTo("+84987654321");
        assertThat(result.getStatus()).isEqualTo(adminCommitsFirst ? UserStatus.ACTIVE : UserStatus.DISABLED);
    }

    private User seed(UserStatus status) {
        return tx.execute(s -> {
            String name = "writer-" + UUID.randomUUID().toString().substring(0, 8);
            User user = new User(name, name + "@example.com", passwords.hash("Passw0rd!"), "Original");
            user.setStatus(status);
            return users.saveAndFlush(user);
        });
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}

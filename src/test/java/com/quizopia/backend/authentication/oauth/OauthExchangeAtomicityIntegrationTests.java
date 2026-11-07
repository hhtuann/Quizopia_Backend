package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.application.SessionIssuanceService;
import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class OauthExchangeAtomicityIntegrationTests {
    @Autowired private OauthIdentityService identities;
    @Autowired private OauthExchangeService exchanges;
    @MockitoSpyBean private SessionIssuanceService sessions;

    @Test
    void issuanceFailureRollsBackCodeConsumptionAndAllowsRetry() {
        String suffix = UUID.randomUUID().toString();
        var user = identities.resolveGoogleUser(new ValidatedOidcIdentity(
                "https://fake-issuer.test", suffix, "atomic" + suffix + "@example.com", true));
        String code = identities.mintExchangeCode(user);
        ClientContext client = new ClientContext("atomic-exchange", "127.0.0.1");
        doThrow(new IllegalStateException("issuance unavailable")).doCallRealMethod()
                .when(sessions).issue(any(), any());
        assertThatThrownBy(() -> exchanges.exchange(code, client)).isInstanceOf(IllegalStateException.class);
        assertThat(exchanges.exchange(code, client).rawRefreshToken()).isNotBlank();
    }
}

package com.quizopia.backend.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Context-free unit tests for {@link ClientRegistry} (B1R4-A2 §1). Proves the created/stopped pairing
 * balances and an intentional over-stop is detected AND self-rebalances to zero (so it cannot mask a
 * real leak in the next test). The {@code @BeforeEach} reset keeps this class self-contained — the
 * integration classes rely on the base {@code @BeforeEach}/{@code @AfterEach} gate (no reset).
 */
class ClientRegistryTest {

    @BeforeEach
    void reset() {
        ClientRegistry.reset();
    }

    @Test
    void createdStoppedBalancesToZero() {
        ClientRegistry.created();
        assertThat(ClientRegistry.outstanding()).isOne();
        ClientRegistry.stopped();
        assertThat(ClientRegistry.outstanding()).isZero();
    }

    @Test
    void twoLiveClientsBalanceIndependently() {
        ClientRegistry.created();
        ClientRegistry.created();
        assertThat(ClientRegistry.outstanding()).isEqualTo(2);
        ClientRegistry.stopped();
        ClientRegistry.stopped();
        assertThat(ClientRegistry.outstanding()).isZero();
    }

    @Test
    void overStopIsDetectedAndRebalances() {
        ClientRegistry.created();
        ClientRegistry.stopped();
        assertThatThrownBy(ClientRegistry::stopped)
                .as("an over-stop must be detected")
                .isInstanceOf(IllegalStateException.class);
        assertThat(ClientRegistry.outstanding())
                .as("over-stop must self-rebalance to zero, not leak a negative counter").isZero();
    }
}

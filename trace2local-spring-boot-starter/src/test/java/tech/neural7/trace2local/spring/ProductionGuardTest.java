package tech.neural7.trace2local.spring;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionGuardTest {

    @Test
    void devProfileIsEnabled() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        assertThat(ProductionGuard.decide(env, false, true, false)).isTrue();
    }

    @Test
    void localstackProfileCountsAsDev() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("localstack");
        assertThat(ProductionGuard.decide(env, false, true, false)).isTrue();
    }

    @Test
    void prodWithoutExplicitEnableAutoDisables() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThat(ProductionGuard.decide(env, false, true, false)).isNull();
    }

    @Test
    void prodWithExplicitEnableFailsBootWithoutEscape() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThatThrownBy(() -> ProductionGuard.decide(env, true, true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEV-TIME");
    }

    @Test
    void prodWithExplicitEnableAndEscapeIsAllowed() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThat(ProductionGuard.decide(env, true, true, true)).isTrue();
    }

    @Test
    void prodWithExplicitDisableIsRespected() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThat(ProductionGuard.decide(env, true, false, false)).isNull();
    }
}

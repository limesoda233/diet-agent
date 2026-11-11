package com.diet.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExperienceConfigurationTest {
    @Test void hostedProfileRequiresSecretsAndRejectsLocalAuth() {
        SecurityConfiguration config = new SecurityConfiguration();
        MockEnvironment hosted = new MockEnvironment();
        hosted.setActiveProfiles("experience");
        assertThatThrownBy(() -> config.bearerAuthenticationFilter(mock(AuthService.class), hosted, "", "invite"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.bearerAuthenticationFilter(mock(AuthService.class), hosted, "secret", ""))
                .isInstanceOf(IllegalStateException.class);
        hosted.setActiveProfiles("experience", "local-auth");
        assertThatThrownBy(() -> config.bearerAuthenticationFilter(mock(AuthService.class), hosted, "secret", "invite"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void experienceDefaultsToMetadataTrace() throws Exception {
        var source = new YamlPropertySourceLoader().load("experience",
                new ClassPathResource("application-experience.yml")).get(0);
        assertThat(source.getProperty("diet.trace.mode")).isEqualTo("${DIET_TRACE_MODE:METADATA}");
    }
}

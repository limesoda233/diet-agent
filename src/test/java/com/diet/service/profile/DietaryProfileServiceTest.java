package com.diet.service.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DietaryProfileServiceTest {
    @Test void profilesAreIsolatedAndSkipDoesNotDiscardSavedRestrictions() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:diet_profile;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(data);
        jdbc.execute("DROP TABLE IF EXISTS user_dietary_profile");
        jdbc.execute("CREATE TABLE user_dietary_profile(user_id BIGINT PRIMARY KEY,allergens CLOB NOT NULL,avoided_foods CLOB NOT NULL,disliked_foods CLOB NOT NULL,spice_preference VARCHAR(16) NOT NULL,onboarding_status VARCHAR(16) NOT NULL,spice_note VARCHAR(128) NOT NULL,updated_at TIMESTAMP NOT NULL)");
        var service = new DietaryProfileService(jdbc, new ObjectMapper());
        assertThat(service.get(7).onboardingStatus()).isEqualTo("NEW");
        service.save(7, new DietaryProfileService.ProfileUpdate(List.of("花生"), List.of("香菜"),
                List.of("折耳根"), "NEVER", "COMPLETED"));
        service.save(8, new DietaryProfileService.ProfileUpdate(List.of(), List.of(), List.of(), "UNSET", "SKIPPED"));
        assertThat(service.get(7).allergens()).containsExactly("花生");
        assertThat(service.get(8).allergens()).isEmpty();
        service.skip(7);
        assertThat(service.get(7).allergens()).containsExactly("花生");
        assertThat(service.get(7).onboardingStatus()).isEqualTo("COMPLETED");
    }

    @Test void invalidAndOversizedFieldsAreRejected() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:diet_profile_validation;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var service = new DietaryProfileService(new JdbcTemplate(data), new ObjectMapper());
        assertThatThrownBy(() -> service.save(7, new DietaryProfileService.ProfileUpdate(
                List.of("花生"), List.of(), List.of(), "SUPER_SPICY", "COMPLETED")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.save(7, new DietaryProfileService.ProfileUpdate(
                List.of("x".repeat(129)), List.of(), List.of(), "UNSET", "COMPLETED")))
                .isInstanceOf(RuntimeException.class);
    }
}

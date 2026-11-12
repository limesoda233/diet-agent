package com.diet.service.profile;

import com.diet.exception.DietException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class DietaryProfileService {
    private static final Set<String> SPICE = Set.of("UNSET", "NEVER", "MILD", "OK");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DietaryProfileService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Profile get(long userId) {
        if (userId <= 0) throw new DietException("用户无效");
        var rows = jdbc.query("SELECT allergens,avoided_foods,disliked_foods,spice_preference,onboarding_status,spice_note "
                + "FROM user_dietary_profile WHERE user_id=?", (row, index) -> new Profile(
                decode(row.getString(1)), decode(row.getString(2)), decode(row.getString(3)),
                row.getString(4), row.getString(5), row.getString(6)), userId);
        return rows.isEmpty() ? new Profile(List.of(), List.of(), List.of(), "UNSET", "NEW", "") : rows.get(0);
    }

    public Profile save(long userId, ProfileUpdate update) {
        if (userId <= 0 || update == null || !SPICE.contains(update.spicePreference())
                || !Set.of("COMPLETED", "SKIPPED").contains(update.onboardingStatus())
                || update.spiceNote() == null || update.spiceNote().length() > 128)
            throw new DietException("饮食限制资料无效");
        var allergens = clean(update.allergens());
        var avoided = clean(update.avoidedFoods());
        var disliked = clean(update.dislikedFoods());
        jdbc.update("INSERT INTO user_dietary_profile(user_id,allergens,avoided_foods,disliked_foods,"
                        + "spice_preference,onboarding_status,spice_note,updated_at) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE allergens=VALUES(allergens),avoided_foods=VALUES(avoided_foods),"
                        + "disliked_foods=VALUES(disliked_foods),spice_preference=VALUES(spice_preference),"
                        + "onboarding_status=VALUES(onboarding_status),spice_note=VALUES(spice_note),updated_at=VALUES(updated_at)",
                userId, encode(allergens), encode(avoided), encode(disliked), update.spicePreference(),
                update.onboardingStatus(), update.spiceNote().strip(), Timestamp.from(Instant.now()));
        return new Profile(allergens, avoided, disliked, update.spicePreference(), update.onboardingStatus(), update.spiceNote().strip());
    }

    public Profile skip(long userId) {
        Profile old = get(userId);
        if (!"NEW".equals(old.onboardingStatus())) return old;
        return save(userId, new ProfileUpdate(old.allergens(), old.avoidedFoods(), old.dislikedFoods(),
                old.spicePreference(), "SKIPPED", old.spiceNote()));
    }

    private List<String> clean(List<String> raw) {
        if (raw == null || raw.size() > 30) throw new DietException("每项最多填写 30 个食物");
        var result = new LinkedHashSet<String>();
        for (String value : raw) {
            if (value == null || value.isBlank() || value.strip().length() > 64)
                throw new DietException("食物名称需为 1 到 64 个字符");
            result.add(value.strip());
        }
        return new ArrayList<>(result);
    }

    private String encode(List<String> values) {
        try { return json.writeValueAsString(values); }
        catch (JsonProcessingException error) { throw new DietException("饮食限制资料无效"); }
    }

    private List<String> decode(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (JsonProcessingException error) { throw new DietException("饮食限制资料读取失败"); }
    }

    public record Profile(List<String> allergens, List<String> avoidedFoods, List<String> dislikedFoods,
                          String spicePreference, String onboardingStatus, String spiceNote) {}
    public record ProfileUpdate(List<String> allergens, List<String> avoidedFoods, List<String> dislikedFoods,
                                String spicePreference, String onboardingStatus, String spiceNote) {
        public ProfileUpdate(List<String> allergens, List<String> avoidedFoods, List<String> dislikedFoods,
                             String spicePreference, String onboardingStatus) {
            this(allergens, avoidedFoods, dislikedFoods, spicePreference, onboardingStatus, "");
        }
    }
}

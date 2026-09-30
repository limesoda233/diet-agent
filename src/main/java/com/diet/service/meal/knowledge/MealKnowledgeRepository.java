package com.diet.service.meal.knowledge;

import com.diet.service.meal.constraint.IngredientCatalog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MealKnowledgeRepository {
    private static final String SELECT_FIELDS = """
            SELECT k.meal_id, k.ingredients, k.description, k.preparation,
                   k.source_type, k.source_reference
            FROM meal_knowledge k
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper json;
    private final IngredientCatalog ingredientCatalog;

    public MealKnowledgeRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, new IngredientCatalog());
    }

    @Autowired
    public MealKnowledgeRepository(JdbcTemplate jdbc,
                                   ObjectMapper json,
                                   IngredientCatalog ingredientCatalog) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
        this.ingredientCatalog = ingredientCatalog;
    }

    public void save(long mealId, MealKnowledge knowledge) {
        if (knowledge == null) {
            delete(mealId);
            return;
        }
        List<String> ingredients = knowledge.ingredients().stream()
                .map(ingredientCatalog::normalize)
                .distinct()
                .toList();
        jdbc.update("""
                        INSERT INTO meal_knowledge
                            (meal_id, ingredients, description, preparation, source_type, source_reference, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, NOW(6))
                        ON DUPLICATE KEY UPDATE
                            ingredients=VALUES(ingredients), description=VALUES(description),
                            preparation=VALUES(preparation), source_type=VALUES(source_type),
                            source_reference=VALUES(source_reference), updated_at=NOW(6)
                        """,
                mealId, encode(ingredients), knowledge.description(), knowledge.preparation(),
                knowledge.sourceType(), knowledge.sourceReference());
    }

    public void delete(long mealId) {
        jdbc.update("DELETE FROM meal_knowledge WHERE meal_id=?", mealId);
    }

    public Map<Long, MealKnowledge> findByMealIds(Collection<Long> mealIds) {
        Map<Long, MealKnowledge> result = new LinkedHashMap<>();
        if (mealIds == null || mealIds.isEmpty()) {
            return result;
        }
        namedJdbc.query(SELECT_FIELDS + " WHERE k.meal_id IN (:ids)", Map.of("ids", mealIds),
                (row, index) -> entry(row)).forEach(entry -> result.put(entry.mealId(), entry.knowledge()));
        return result;
    }

    public Optional<MealKnowledge> findPersonal(long mealId, long userId) {
        List<Entry> rows = jdbc.query(SELECT_FIELDS + """
                        JOIN meal_item m ON m.id=k.meal_id
                        WHERE k.meal_id=? AND m.source_type='PERSONAL' AND m.owner_user_id=?
                        """,
                (row, index) -> entry(row), mealId, userId);
        return rows.stream().findFirst().map(Entry::knowledge);
    }

    private Entry entry(ResultSet row) throws SQLException {
        return new Entry(row.getLong("meal_id"), new MealKnowledge(
                decode(row.getString("ingredients")), row.getString("description"),
                row.getString("preparation"), row.getString("source_type"),
                row.getString("source_reference")));
    }

    private List<String> decode(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {
            });
        } catch (Exception error) {
            throw new IllegalStateException("餐食知识 JSON 数据无效", error);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("无法编码餐食知识", error);
        }
    }

    private record Entry(long mealId, MealKnowledge knowledge) {
    }
}

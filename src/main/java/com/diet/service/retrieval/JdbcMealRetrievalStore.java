package com.diet.service.retrieval;

import com.diet.service.meal.constraint.IngredientCatalog;

import com.diet.service.meal.knowledge.MealKnowledge;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import java.util.function.IntSupplier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class JdbcMealRetrievalStore implements MealRetrievalStore {
    private static final Map<String,String> COLUMNS = Map.of("mealTime","meal_time","mood","mood","scene","scene","healthGoal","health_goal","cuisine","cuisine","taste","taste","convenience","convenience");
    private static final String SELECT = "SELECT m.*, k.ingredients, k.description, k.preparation, k.source_type AS knowledge_source, k.source_reference FROM meal_item m LEFT JOIN meal_knowledge k ON k.meal_id=m.id ";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    private final IngredientCatalog catalog;
    private final RetrievalProperties properties;
    public JdbcMealRetrievalStore(JdbcTemplate jdbc, ObjectMapper json, IngredientCatalog catalog) {
        this(jdbc,json,catalog,new RetrievalProperties());
    }
    @Autowired
    public JdbcMealRetrievalStore(JdbcTemplate jdbc, ObjectMapper json, IngredientCatalog catalog, RetrievalProperties properties) {
        this.jdbc=jdbc; this.named=new NamedParameterJdbcTemplate(jdbc); this.json=json; this.catalog=catalog; this.properties=properties;
    }
    @Override public List<MealDocument> structured(MealRetrievalRequest request, int limit) {
        catalog.validate(request.constraints());
        var params = new HashMap<String,Object>();
        params.put("source", request.sourceMode().name()); params.put("user", request.userId()); params.put("limit", Math.min(50, Math.max(1, limit)));
        StringBuilder where = new StringBuilder(" WHERE m.source_type=:source AND ");
        where.append(request.sourceMode() == SourceMode.PUBLIC ? "m.owner_user_id IS NULL" : "m.owner_user_id=:user");
        if (!request.excludeMealIds().isEmpty()) { where.append(" AND m.id NOT IN (:excludedIds)"); params.put("excludedIds", request.excludeMealIds()); }
        overlap(where, params, "m.meal_time", request.slots().mealTime(), false);
        var c = request.constraints();
        if (!c.requiredIngredients().isEmpty() || !c.excludedIngredients().isEmpty()) where.append(" AND k.ingredients IS NOT NULL AND JSON_LENGTH(k.ingredients)>0");
        for (String ingredient : c.requiredIngredients()) {
            String key = "p" + params.size(); params.put(key, encode(List.of(ingredient)));
            where.append(" AND JSON_CONTAINS(k.ingredients, :").append(key).append(")");
        }
        overlap(where, params, "k.ingredients", c.excludedIngredients(), true);
        c.requiredSlots().forEach((key, values) -> overlap(where, params, "m." + COLUMNS.get(key), values, false));
        c.excludedSlots().forEach((key, values) -> overlap(where, params, "m." + COLUMNS.get(key), values, true));
        var scores = new ArrayList<String>();
        RetrievalSlots.from(request.slots()).forEach((key, values) -> {
            if (!key.equals("mealTime") && !values.isEmpty()) {
                String parameter = "soft" + scores.size(); params.put(parameter, encode(values));
                scores.add("(SELECT COUNT(*) FROM JSON_TABLE(:" + parameter + ", '$[*]' COLUMNS (v VARCHAR(64) PATH '$')) w WHERE JSON_CONTAINS(m." + COLUMNS.get(key) + ", JSON_QUOTE(w.v))) / " + values.size());
            }
        });
        String score = scores.isEmpty() ? "0" : "(" + String.join(" + ", scores) + ") / " + scores.size();
        return named.query(SELECT.replace(" FROM meal_item", ", (" + score + ") AS retrieval_score FROM meal_item") + where + " ORDER BY retrieval_score DESC, m.id ASC LIMIT :limit", params, (rs, row) -> document(rs));
    }
    private void overlap(StringBuilder sql, Map<String,Object> params, String column, List<String> values, boolean negative) {
        if (values.isEmpty()) return;
        String key="p" + params.size(); params.put(key, encode(values));
        sql.append(" AND ").append(negative ? "NOT " : "").append("JSON_OVERLAPS(").append(column).append(", :").append(key).append(")");
    }
    @Override public Map<Long,MealDocument> find(List<Long> ids) {
        var result = new LinkedHashMap<Long,MealDocument>();
        if (!ids.isEmpty()) named.query(SELECT + " WHERE m.id IN (:ids)", Map.of("ids", ids), (rs,row) -> document(rs)).forEach(d -> result.put(d.meal().id(), d));
        return result;
    }
    public void saveKnowledge(long mealId, MealKnowledge knowledge) {
        if (knowledge == null) { deleteKnowledge(mealId); return; }
        var ingredients = knowledge.ingredients().stream().map(catalog::normalize).distinct().toList();
        jdbc.update("INSERT INTO meal_knowledge VALUES(?,?,?,?,?,?,NOW(6)) ON DUPLICATE KEY UPDATE ingredients=VALUES(ingredients),description=VALUES(description),preparation=VALUES(preparation),source_type=VALUES(source_type),source_reference=VALUES(source_reference),updated_at=NOW(6)",
                mealId, encode(ingredients), knowledge.description(), knowledge.preparation(), knowledge.sourceType(), knowledge.sourceReference());
    }
    public void deleteKnowledge(long mealId) { jdbc.update("DELETE FROM meal_knowledge WHERE meal_id=?", mealId); }
    @Override public List<Long> idsAfter(long id, int limit) { return jdbc.queryForList("SELECT id FROM meal_item WHERE id>? ORDER BY id LIMIT ?", Long.class, id, limit); }
    @Override public void enqueue(long id) {
        jdbc.update("INSERT INTO meal_index_task(meal_id,updated_at) VALUES(?,NOW(6)) ON DUPLICATE KEY UPDATE revision=revision+1,updated_at=NOW(6)", id);
    }
    @Override public void enqueueRepair(long id) {
        int inserted=jdbc.update("INSERT IGNORE INTO meal_index_task(meal_id,updated_at) VALUES(?,NOW(6))",id);
        if(inserted==0) jdbc.update("UPDATE meal_index_task t JOIN meal_index_progress p ON p.meal_id=t.meal_id AND p.collection_name=? SET t.revision=t.revision+1,t.updated_at=NOW(6) WHERE t.meal_id=? AND p.applied_revision>=t.revision",collection(),id);
    }
    @Override public List<IndexTask> pending(int limit) {
        return jdbc.query("SELECT t.meal_id,t.revision,IF(p.revision=t.revision,p.attempts,0) FROM meal_index_task t LEFT JOIN meal_index_progress p ON p.meal_id=t.meal_id AND p.collection_name=? WHERE t.revision>COALESCE(p.applied_revision,0) AND (p.revision IS NULL OR p.revision<>t.revision OR p.attempts<5) ORDER BY t.updated_at,t.meal_id LIMIT ?", (rs,n) -> new IndexTask(rs.getLong(1),rs.getLong(2),rs.getInt(3)),collection(),limit);
    }
    @Override public void complete(IndexTask task) {
        jdbc.update("INSERT INTO meal_index_progress(collection_name,meal_id,revision,applied_revision) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),applied_revision=VALUES(applied_revision),attempts=0,last_error=NULL",collection(),task.mealId(),task.revision(),task.revision());
    }
    @Override public void fail(IndexTask task, String category) {
        String safe = category != null && category.matches("[a-zA-Z0-9_]{1,100}") ? category : "index_failure";
        jdbc.update("INSERT INTO meal_index_progress(collection_name,meal_id,revision,attempts,last_error) VALUES(?,?,?,1,?) ON DUPLICATE KEY UPDATE attempts=IF(revision=VALUES(revision),attempts+1,1),revision=VALUES(revision),last_error=VALUES(last_error)",collection(),task.mealId(),task.revision(),safe);
    }
    public List<Map<String,Object>> status() { return jdbc.queryForList("SELECT t.meal_id,t.revision,COALESCE(p.applied_revision,0) AS applied_revision,IF(p.revision=t.revision,p.attempts,0) AS attempts,IF(p.revision=t.revision,p.last_error,NULL) AS last_error FROM meal_index_task t LEFT JOIN meal_index_progress p ON p.meal_id=t.meal_id AND p.collection_name=? WHERE t.revision>COALESCE(p.applied_revision,0) ORDER BY t.meal_id",collection()); }
    public void retryFailed() { jdbc.update("UPDATE meal_index_progress SET attempts=0,last_error=NULL WHERE collection_name=?",collection()); }
    private String collection() { return properties.getQdrant().getCollection(); }

    /** A database-wide lock serializes vector writes across workers and rebuild commands. */
    public int withIndexLock(IntSupplier work) {
        return jdbc.execute((ConnectionCallback<Integer>) connection -> {
            String lockName="diet_index:"+connection.getCatalog();
            try(var statement=connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                statement.setString(1,lockName);
                try(var result=statement.executeQuery()) {
                    if(!result.next() || result.getInt(1)!=1) throw new RetrievalDependencyException("index_worker_busy");
                }
            }
            try { return work.getAsInt(); }
            finally {
                try(var statement=connection.prepareStatement("SELECT RELEASE_LOCK(?)")) { statement.setString(1,lockName); statement.execute(); }
            }
        });
    }
    private MealDocument document(ResultSet rs) throws SQLException {
        var slots = new SlotBundle(strings(rs.getString("meal_time")),strings(rs.getString("mood")),strings(rs.getString("scene")),strings(rs.getString("health_goal")),strings(rs.getString("cuisine")),strings(rs.getString("taste")),strings(rs.getString("convenience")));
        MealKnowledge knowledge = rs.getString("knowledge_source") == null ? null : new MealKnowledge(strings(rs.getString("ingredients")),rs.getString("description"),rs.getString("preparation"),rs.getString("knowledge_source"),rs.getString("source_reference"));
        return new MealDocument(new MealItem(rs.getLong("id"),SourceMode.valueOf(rs.getString("source_type")),rs.getObject("owner_user_id",Long.class),rs.getString("name"),slots,0,knowledge),knowledge);
    }
    private List<String> strings(String raw) {
        try { return raw == null ? List.of() : json.readValue(raw, new TypeReference<List<String>>(){}); }
        catch (Exception e) { throw new IllegalStateException("餐食 JSON 数据无效",e); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("无法编码检索参数",e); }
    }
}

package com.diet.evaluation.retrieval;

import com.diet.mapper.MealMapper;
import com.diet.model.MealRankRequest;
import com.diet.model.MealSearchRequest;
import com.diet.service.meal.*;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.*;
import java.net.URI;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

final class MySqlRetrievalAdapter implements RetrievalStrategy {
    Connection evaluationConnection() { return connection; }
    private final Connection connection;
    private final SqlSession session;
    private final MealSearchService search;
    private final MealRankService rank = new MealRankService();

    static MySqlRetrievalAdapter fromEnvironment(List<RetrievalDataset.Meal> meals) throws Exception {
        String url = System.getenv("RETRIEVAL_EVAL_DB_URL");
        validateUrl(url);
        String user = System.getenv("RETRIEVAL_EVAL_DB_USERNAME");
        if(user == null || user.isBlank()) throw new IllegalArgumentException("RETRIEVAL_EVAL_DB_USERNAME is required");
        var connection=DriverManager.getConnection(url,user,Objects.requireNonNullElse(System.getenv("RETRIEVAL_EVAL_DB_PASSWORD"),""));
        try { return new MySqlRetrievalAdapter(connection,meals); }
        catch(Exception e) { connection.close(); throw e; }
    }
    static void validateUrl(String url) {
        if(url == null || !url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("dedicated RETRIEVAL_EVAL_DB_URL is required");
        URI parsed=URI.create(url.substring(5));
        if(!Set.of("localhost","127.0.0.1").contains(parsed.getHost()) || !"/diet_retrieval_eval".equals(parsed.getPath()) || parsed.getUserInfo()!=null
                || (parsed.getQuery()!=null && parsed.getQuery().matches("(?i).*(password|user)=.*")))
            throw new IllegalArgumentException("benchmark requires local diet_retrieval_eval database without URL credentials");
    }
    private MySqlRetrievalAdapter(Connection connection,List<RetrievalDataset.Meal> meals) throws Exception {
        this.connection=connection;
        try(var statement=connection.createStatement()) {
            statement.execute("""
                CREATE TEMPORARY TABLE meal_item (
                  id BIGINT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, owner_user_id BIGINT,
                  name VARCHAR(200) NOT NULL, meal_time JSON, mood JSON, scene JSON, health_goal JSON,
                  cuisine JSON, taste JSON, convenience JSON, created_at DATETIME, updated_at DATETIME
                )
                """);
        }
        ObjectMapper mapper=new ObjectMapper();
        try(var insert=connection.prepareStatement("INSERT INTO meal_item VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for(var m:meals) {
                insert.setLong(1,m.id()); insert.setString(2,m.sourceMode().name());
                if(m.ownerUserId()==null) insert.setNull(3,Types.BIGINT); else insert.setLong(3,m.ownerUserId());
                insert.setString(4,m.name());
                for(int i=0;i<RetrievalDataset.SLOT_NAMES.size();i++) insert.setString(i+5,mapper.writeValueAsString(m.slots().get(RetrievalDataset.SLOT_NAMES.get(i))));
                Timestamp timestamp=Timestamp.valueOf(LocalDateTime.parse(m.updatedAt()));
                insert.setTimestamp(12,timestamp); insert.setTimestamp(13,timestamp); insert.addBatch();
            }
            insert.executeBatch();
        }
        Configuration configuration=new Configuration();
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        try(var xml=MySqlRetrievalAdapter.class.getResourceAsStream("/mapper/MealMapper.xml")) {
            if(xml==null) throw new IllegalStateException("production MealMapper.xml missing");
            new XMLMapperBuilder(xml,configuration,"mapper/MealMapper.xml",configuration.getSqlFragments()).parse();
        }
        session=new SqlSessionFactoryBuilder().build(configuration).openSession(connection);
        // CRUD validation is never called by search; no fake slot mapper or database substitute is used.
        var mealService=new MealService(session.getMapper(MealMapper.class),null,new JsonService(mapper));
        search=new MealSearchService(mealService);
    }
    @Override public Retrieved retrieve(RetrievalStrategy.Input query) {
        var slots=RetrievalDataset.slots(query.slots());
        var candidates=search.search(new MealSearchRequest(query.sourceMode(),query.userId(),slots,query.excludeMealIds()));
        var ranked=rank.rank(new MealRankRequest(candidates,slots,query.excludeMealIds()));
        return new Retrieved(candidates,ranked.stream().limit(3).toList());
    }
    String mysqlVersion() throws SQLException { return connection.getMetaData().getDatabaseProductVersion(); }
    @Override public String name() { return "mysql-json-overlaps-production-v1"; }
    @Override public void close() throws SQLException {
        try { session.close(); } finally { connection.close(); }
    }
}

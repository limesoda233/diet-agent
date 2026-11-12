package com.diet.service.meal.intake;

import com.diet.enums.SourceMode;
import com.diet.mapper.*;
import com.diet.model.*;
import com.diet.service.meal.MealService;
import com.diet.service.meal.nutrition.*;
import com.diet.service.meal.knowledge.*;
import com.diet.service.session.SessionStateService;
import com.diet.service.slot.SlotOptionService;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.intent.*;
import com.diet.service.meal.constraint.*;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.risk.RiskGuardService;
import com.diet.service.session.SessionService;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.JsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealDraftSaveTransactionTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    SessionStateService sessions;
    MealDraftSaveService saves;
    SessionState state;
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc=context.getBean(JdbcTemplate.class);
        jdbc.execute("CREATE TABLE diet_sessions(id VARCHAR(80) PRIMARY KEY,user_id BIGINT,phase VARCHAR(40),slots CLOB,last_recommendations CLOB,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE meal_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,source_type VARCHAR(30),owner_user_id BIGINT,name VARCHAR(128),meal_time CLOB,mood CLOB,scene CLOB,health_goal CLOB,cuisine CLOB,taste CLOB,convenience CLOB,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE meal_knowledge(meal_id BIGINT PRIMARY KEY,ingredients CLOB,description CLOB,preparation CLOB,source_type VARCHAR(40),source_reference VARCHAR(300),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE recommend_feedback(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,item_id BIGINT)");
        jdbc.execute("CREATE TABLE personal_meal_name_guard(user_id BIGINT,normalized_name VARCHAR(128),created_at TIMESTAMP,PRIMARY KEY(user_id,normalized_name))");
        jdbc.execute("CREATE TABLE meal_nutrition_variant(id BIGINT AUTO_INCREMENT PRIMARY KEY,meal_id BIGINT NOT NULL,variant_key VARCHAR(64) NOT NULL,label VARCHAR(64) NOT NULL,template_code VARCHAR(32) NOT NULL,selections CLOB NOT NULL,estimate_json CLOB NOT NULL,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(meal_id,variant_key))");
        jdbc.execute("CREATE TABLE diet_slot_option(id BIGINT AUTO_INCREMENT PRIMARY KEY,slot_name VARCHAR(40),option_value VARCHAR(40),enabled INT,sort_order INT)");
        jdbc.update("INSERT INTO diet_slot_option(slot_name,option_value,enabled,sort_order) VALUES('mealTime','午餐',1,1)");
        sessions=context.getBean(SessionStateService.class); saves=context.getBean(MealDraftSaveService.class);
        state=sessions.create(7L,SourceMode.PUBLIC).withMealDraft(new MealDraft("draft","番茄面 午餐",new MealDraftData("番茄面",List.of("午餐"),null,null,null,null,null,null,new MealKnowledge(List.of("番茄"),"用户描述","煮面","USER_PROVIDED","")),List.of(),List.of(),Map.of(),MealDraftStatus.READY,null));
        sessions.save(state);
    }
    @AfterEach void close() { context.close(); }
    @Test void receiptUpdateFailureRollsBackMealAndKnowledgeThenCanRetry() {
        jdbc.execute("ALTER TABLE diet_sessions ADD CONSTRAINT reject_saved CHECK (CAST(slots AS VARCHAR) NOT LIKE '%SAVED%')");
        assertThatThrownBy(() -> saves.save(7L,"draft",state)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_knowledge",Integer.class)).isZero();
        assertThat(sessions.loadOrCreate(state.sessionId(),7L,SourceMode.PUBLIC).mealDraft().status()).isEqualTo(MealDraftStatus.READY);
        jdbc.execute("ALTER TABLE diet_sessions DROP CONSTRAINT reject_saved");
        assertThat(saves.save(7L,"draft",state).mealDraft().status()).isEqualTo(MealDraftStatus.SAVED);
    }
    @Test void successfulAndRepeatedSavePersistOnePersonalMealWithReceipt() {
        var saved=saves.save(7L,"draft",state);
        var reloaded=sessions.loadOrCreate(state.sessionId(),7L,SourceMode.PUBLIC);
        assertThat(reloaded.mealDraft().savedMealId()).isEqualTo(saved.mealDraft().savedMealId()).isNotNull();
        assertThat(saves.save(7L,"draft",reloaded).mealDraft().savedMealId()).isEqualTo(saved.mealDraft().savedMealId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PERSONAL' AND owner_user_id=7",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_type FROM meal_knowledge",String.class)).isEqualTo("USER_PROVIDED");
    }
    @Test void reviewedNutritionVariantIsSavedWithChatDraft() {
        var data=new MealDraftData("麻辣烫",List.of("午餐"),null,null,null,null,null,null,null);
        var changed=state.withMealDraft(new MealDraft("draft","麻辣烫 午餐",data,List.of(),List.of(),Map.of(),MealDraftStatus.READY,null));
        sessions.save(changed);
        var choices=new NutritionTemplateService().draft("麻辣烫","麻辣烫，不加辣",Map.of()).selections();
        var variant=new MealNutritionVariantService.Input("清汤版","MALATANG",choices);
        saves.save(7L,"draft",changed,null,false,variant);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_nutrition_variant",Integer.class)).isEqualTo(1);
    }
    @Test void reviewedTagsReplaceSuggestedTagsBeforeSave() {
        jdbc.update("INSERT INTO diet_slot_option(slot_name,option_value,enabled,sort_order) VALUES('mealTime','晚餐',1,2)");
        var saved=saves.save(7L,"draft",state,Map.of("mealTime",List.of("晚餐"),
                "mood",List.of(),"scene",List.of(),"healthGoal",List.of(),"cuisine",List.of(),
                "taste",List.of(),"convenience",List.of()));
        assertThat(saved.mealDraft().data().mealTime()).containsExactly("晚餐");
        assertThat(jdbc.queryForObject("SELECT meal_time FROM meal_item",String.class)).contains("晚餐").doesNotContain("午餐");
    }
    @Test void duplicateRequiresConfirmationThenMergesMealTimesIntoOriginal() {
        jdbc.update("INSERT INTO diet_slot_option(slot_name,option_value,enabled,sort_order) VALUES('mealTime','早餐',1,0)");
        var meals=context.getBean(MealService.class);
        var old=meals.createPersonalMeal(7L,new MealRequest("番茄面",List.of("早餐"),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of()));
        jdbc.update("INSERT INTO meal_item(source_type,owner_user_id,name,meal_time,mood,scene,health_goal,cuisine,taste,convenience,created_at,updated_at) "
                + "SELECT source_type,owner_user_id,name,meal_time,mood,scene,health_goal,cuisine,taste,convenience,created_at,updated_at FROM meal_item WHERE id=?",old.id());
        Long extra=jdbc.queryForObject("SELECT MAX(id) FROM meal_item",Long.class);
        jdbc.update("INSERT INTO recommend_feedback(user_id,item_id) VALUES(?,?)",7,extra);
        assertThatThrownBy(() -> saves.save(7L,"draft",state))
                .isInstanceOf(com.diet.exception.DuplicateMealException.class);
        var awaiting=state.withMealDraft(state.mealDraft().awaitingMerge());
        sessions.save(awaiting);
        var merged=saves.save(7L,"draft",awaiting,null,true);
        assertThat(merged.mealDraft().savedMealId()).isEqualTo(old.id());
        assertThat(merged.mealDraft().data().mealTime()).containsExactly("午餐");
        assertThat(jdbc.queryForObject("SELECT meal_time FROM meal_item",String.class)).contains("早餐","午餐");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT item_id FROM recommend_feedback",Long.class)).isEqualTo(old.id());
    }
    @Test void wrongUserStaleCancelledAndIncompleteCannotWrite() {
        assertThatThrownBy(() -> saves.save(8L,"draft",state)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> saves.save(7L,"stale",state)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> saves.save(7L,"draft",state.withMealDraft(state.mealDraft().cancelled()))).isInstanceOf(RuntimeException.class);
        var incomplete=new MealDraft("draft","",new MealDraftData("",List.of(),null,null,null,null,null,null,null),List.of(),List.of(),Map.of(),MealDraftStatus.READY,null);
        assertThatThrownBy(() -> saves.save(7L,"draft",state.withMealDraft(incomplete))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item",Integer.class)).isZero();
    }
    @Test void concurrentRepeatedChatActionReturnsOneReceiptAndOneMeal() throws Exception {
        var trace=mock(AgentTraceService.class);
        var orchestrator=new DietOrchestratorService(mock(SessionService.class),sessions,mock(IntentAgentService.class),
                mock(IntentReviseService.class),new SlotMergeService(),mock(ClarifyAgentService.class),
                mock(com.diet.service.meal.MealSearchService.class),mock(com.diet.service.meal.MealRankService.class),
                mock(com.diet.service.meal.MealPlanService.class),mock(RecommendResponseAgentService.class),
                context.getBean(MealService.class),mock(RiskGuardService.class),trace,
                new ConstraintParser(new IngredientCatalog()));
        orchestrator.setMealIntakeWorkflow(new MealIntakeWorkflow(mock(MealDraftService.class),saves,trace));
        var request=new ChatRequest(state.sessionId(),"确认保存",SourceMode.PUBLIC,
                Map.of("mealDraftAction",Map.of("draftId","draft","action","SAVE")));
        var ready=new CountDownLatch(2);
        var go=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<ChatResponse> call=() -> { ready.countDown(); go.await(); return orchestrator.dietChat(7L,request); };
            var first=pool.submit(call);
            var second=pool.submit(call);
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            go.countDown();
            Long firstId=first.get(10,TimeUnit.SECONDS).mealDraft().savedMealId();
            Long secondId=second.get(10,TimeUnit.SECONDS).mealDraft().savedMealId();
            assertThat(firstId).isNotNull().isEqualTo(secondId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item",Integer.class)).isEqualTo(1);
        } finally {
            go.countDown(); pool.shutdownNow();
        }
    }
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource() { var data=new JdbcDataSource(); data.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1"); return data; }
        @Bean DataSourceTransactionManager transactionManager(DataSource data) { return new DataSourceTransactionManager(data); }
        @Bean JdbcTemplate jdbc(DataSource data) { return new JdbcTemplate(data); }
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean org.apache.ibatis.session.SqlSessionFactory factory(DataSource data) throws Exception {
            var factory=new SqlSessionFactoryBean(); factory.setDataSource(data);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*Mapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate template(org.apache.ibatis.session.SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean MealMapper meals(SqlSessionTemplate template) { return template.getMapper(MealMapper.class); }
        @Bean SessionMapper sessionMapper(SqlSessionTemplate template) { return template.getMapper(SessionMapper.class); }
        @Bean SlotOptionMapper slotMapper(SqlSessionTemplate template) { return template.getMapper(SlotOptionMapper.class); }
        @Bean SlotOptionService slots(SlotOptionMapper mapper) { return new SlotOptionService(mapper); }
        @Bean JsonService jsonService(ObjectMapper json) { return new JsonService(json); }
        @Bean MealKnowledgeRepository knowledge(JdbcTemplate jdbc,ObjectMapper json) { return new MealKnowledgeRepository(jdbc,json); }
        @Bean MealService mealService(MealMapper mapper,SlotOptionService slots,JsonService json,MealKnowledgeRepository knowledge) { return new MealService(mapper,slots,json,knowledge); }
        @Bean NutritionTemplateService nutritionTemplateService() { return new NutritionTemplateService(); }
        @Bean MealNutritionVariantService nutritionVariants(JdbcTemplate jdbc,ObjectMapper json,NutritionTemplateService templates) {
            return new MealNutritionVariantService(jdbc,json,templates);
        }
        @Bean SessionStateService sessions(SessionMapper mapper,ObjectMapper json) { return new SessionStateService(mapper,json); }
        @Bean MealDraftSaveService saves(MealService meals,SessionStateService sessions) { return new MealDraftSaveService(meals,sessions); }
    }
}

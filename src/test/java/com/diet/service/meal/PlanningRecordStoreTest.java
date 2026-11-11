package com.diet.service.meal;

import com.diet.service.meal.planning.runtime.PlanningRecordStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlanningRecordStoreTest {
    @Test void slowFinalInsertRollsBackWhenCommitDeadlineExpires() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds){
            @Override public int update(String sql,Object... args) {
                int n=super.update(sql,args);
                if(sql.startsWith("INSERT INTO meal_plan_version"))java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
                return n;
            }
        };
        jdbc.execute("CREATE TABLE diet_sessions(id VARCHAR(80) PRIMARY KEY,user_id BIGINT NOT NULL)");jdbc.update("INSERT INTO diet_sessions VALUES ('s1',1)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migrations/006_meal_planning.sql")).execute(ds);
        var store=new PlanningRecordStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),new ObjectMapper());
        assertThrows(IllegalStateException.class,()->store.save(1L,"s1","t1","COMPLETE","{}","{}","{}","{}",System.nanoTime()+50_000_000L));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM meal_plan",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM meal_plan_version",Integer.class));
    }
    @Test void finalVersionAndFeedbackAreBoundToUserSessionTraceTaskAndMeal() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE diet_sessions(id VARCHAR(80) PRIMARY KEY,user_id BIGINT NOT NULL)");
        jdbc.update("INSERT INTO diet_sessions VALUES ('s1',1)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migrations/006_meal_planning.sql")).execute(ds);
        var store=new PlanningRecordStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),new ObjectMapper());
        String plan=store.save(1L,"s1","t1","COMPLETE","{}","{}","{}","{\"days\":[{\"blocks\":[{\"taskId\":\"d0-dinner\",\"selectedMeal\":{\"mealId\":3}}]}]}");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM meal_plan_version WHERE plan_id=?",Integer.class,plan));
        assertDoesNotThrow(()->store.assertFeedback(1L,"s1","t1",plan,1,"d0-dinner",3L));
        assertTrue(store.requiresPlanIdentity(1L,"s1","t1"));
        assertFalse(store.requiresPlanIdentity(2L,"s1","t1"));
        store.recordFeedback(1L,new com.diet.model.FeedbackRequest("s1",3L,"LIKE",5,"口味合适","t1").planId(plan).planVersion(1).taskId("d0-dinner"));
        assertEquals("d0-dinner",jdbc.queryForObject("SELECT task_id FROM meal_plan_feedback WHERE plan_id=?",String.class,plan));
        assertThrows(IllegalArgumentException.class,()->store.assertFeedback(2L,"s1","t1",plan,1,"d0-dinner",3L));
        assertThrows(IllegalArgumentException.class,()->store.assertFeedback(1L,"s1","t1",plan,1,"d1-dinner",3L));
        assertThrows(IllegalArgumentException.class,()->store.assertFeedback(1L,"s1","other",plan,1,"d0-dinner",3L));
        assertThrows(IllegalArgumentException.class,()->store.save(2L,"s1","t2","COMPLETE","{}","{}","{}","{}"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM meal_plan",Integer.class));
    }
}

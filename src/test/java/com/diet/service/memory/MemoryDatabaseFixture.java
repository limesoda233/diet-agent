package com.diet.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** Shared real SQL fixture; behavior tests are not inherited/repeated by unrelated suites. */
abstract class MemoryDatabaseFixture {
    JdbcTemplate jdbc;
    MemoryStore store;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE diet_sessions(id VARCHAR(80) PRIMARY KEY,user_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE diet_messages(id BIGINT AUTO_INCREMENT PRIMARY KEY,session_id VARCHAR(80),role VARCHAR(16),content TEXT,intent VARCHAR(64),agent_trace_id VARCHAR(80),created_at TIMESTAMP)");
        jdbc.update("INSERT INTO diet_sessions VALUES ('s1',1),('s2',2)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migrations/005_user_memory.sql")).execute(ds);
        store=new MemoryStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),new ObjectMapper());
    }
}

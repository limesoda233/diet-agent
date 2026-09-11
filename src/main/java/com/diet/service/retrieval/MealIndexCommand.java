package com.diet.service.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.Path;
import java.util.*;

@Component
@ConditionalOnExpression("'${diet.retrieval.index-command:}' != ''")
public class MealIndexCommand implements ApplicationRunner {
    @org.springframework.beans.factory.annotation.Value("${spring.main.web-application-type:servlet}")
    private String webApplicationType;
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void closeCommandContext(org.springframework.boot.context.event.ApplicationReadyEvent event) {
        event.getApplicationContext().close();
    }
    private final MealIndexService index;
    private final JdbcMealRetrievalStore store;
    private final JdbcTemplate jdbc;
    private final RetrievalProperties properties;
    private final TransactionTemplate transaction;
    public MealIndexCommand(MealIndexService index,JdbcMealRetrievalStore store,JdbcTemplate jdbc,RetrievalProperties properties,PlatformTransactionManager manager) {
        this.index=index; this.store=store; this.jdbc=jdbc; this.properties=properties; transaction=new TransactionTemplate(manager);
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if(!"none".equalsIgnoreCase(webApplicationType)) throw new IllegalArgumentException("索引命令必须显式设置 spring.main.web-application-type=none");
        switch(properties.getIndexCommand()) {
            case "migrate" -> jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migrations/002_meal_retrieval.sql")); return null;
            });
            case "status" -> System.out.println(new ObjectMapper().writeValueAsString(store.status()));
            case "retry" -> index.retry();
            case "rebuild" -> index.rebuild();
            case "import" -> importPublicKnowledge();
            default -> throw new IllegalArgumentException("index-command 必须为 migrate/status/retry/rebuild/import");
        }
        System.out.println("Meal index command completed: " + properties.getIndexCommand());
    }
    private void importPublicKnowledge() throws Exception {
        if(properties.getImportFile().isBlank()) throw new IllegalArgumentException("import-file 不能为空");
        var entries=new ObjectMapper().readValue(Path.of(properties.getImportFile()).toFile(),ImportEntry[].class);
        transaction.executeWithoutResult(status -> {
            for(var entry:entries) {
                var document=store.find(List.of(entry.mealId())).get(entry.mealId());
                if(document==null || document.meal().sourceType()!=com.diet.enums.SourceMode.PUBLIC || document.meal().ownerUserId()!=null)
                    throw new IllegalArgumentException("导入仅允许当前公共餐食 ID");
                if(entry.knowledge()==null || !entry.knowledge().sourceType().equals("IMPORTED") || entry.knowledge().sourceReference().isBlank())
                    throw new IllegalArgumentException("导入知识必须有 IMPORTED 来源及来源引用");
                store.saveKnowledge(entry.mealId(),entry.knowledge()); store.enqueue(entry.mealId());
            }
        });
    }
    public record ImportEntry(long mealId,MealKnowledge knowledge) {}
}

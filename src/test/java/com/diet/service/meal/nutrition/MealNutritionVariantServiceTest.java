package com.diet.service.meal.nutrition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.diet.service.profile.DietaryProfileService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealNutritionVariantServiceTest {
    @Test void renamingToAnotherTemplateClearsStalePortionVersions() {
        var data=new JdbcDataSource();
        data.setURL("jdbc:h2:mem:nutrition_rename;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE meal_item(id BIGINT PRIMARY KEY,source_type VARCHAR(16),owner_user_id BIGINT,name VARCHAR(128))");
        jdbc.execute("CREATE TABLE meal_nutrition_variant(id BIGINT AUTO_INCREMENT PRIMARY KEY,meal_id BIGINT NOT NULL,variant_key VARCHAR(64) NOT NULL,label VARCHAR(64) NOT NULL,template_code VARCHAR(32) NOT NULL,selections CLOB NOT NULL,estimate_json CLOB NOT NULL,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(meal_id,variant_key))");
        jdbc.update("INSERT INTO meal_item VALUES (11,'PERSONAL',7,'麻辣烫')");
        var service=new MealNutritionVariantService(jdbc,new ObjectMapper(),new NutritionTemplateService());
        var draft=service.suggest("麻辣烫","不辣",Map.of());
        service.savePersonal(7,11,new MealNutritionVariantService.Input("常吃","MALATANG",draft.selections()));
        service.clearWhenTemplateChanges(7,11,"麻辣烫","麻辣烫大份");
        assertThat(service.list(7,11)).hasSize(1);
        service.clearWhenTemplateChanges(7,11,"麻辣烫","炸串");
        assertThat(service.list(7,11)).isEmpty();
    }
    @Test void oneMealKeepsMultipleCompositionsAndOtherUsersCannotReadThem() {
        var data=new JdbcDataSource();
        data.setURL("jdbc:h2:mem:nutrition_variants;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE meal_item(id BIGINT PRIMARY KEY,source_type VARCHAR(16),owner_user_id BIGINT,name VARCHAR(128))");
        jdbc.execute("CREATE TABLE meal_nutrition_variant(id BIGINT AUTO_INCREMENT PRIMARY KEY,meal_id BIGINT NOT NULL,variant_key VARCHAR(64) NOT NULL,label VARCHAR(64) NOT NULL,template_code VARCHAR(32) NOT NULL,selections CLOB NOT NULL,estimate_json CLOB NOT NULL,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(meal_id,variant_key))");
        jdbc.update("INSERT INTO meal_item VALUES (11,'PERSONAL',7,'麻辣烫')");
        jdbc.update("INSERT INTO meal_item VALUES (12,'PUBLIC',NULL,'炸串')");
        var service=new MealNutritionVariantService(jdbc,new ObjectMapper(),new NutritionTemplateService());
        var base=service.suggest("麻辣烫","不加辣",Map.of());
        var first=service.savePersonal(7,11,new MealNutritionVariantService.Input("清汤少主食","MALATANG",base.selections()));
        var second=service.savePersonal(7,11,new MealNutritionVariantService.Input("大份麻酱", "MALATANG",
                Map.of("size","LARGE","composition","BALANCED","starch","REGULAR","oil","NORMAL",
                        "sesame","NORMAL","sugar","NONE","spice","MILD")));
        assertThat(service.list(7,11)).hasSize(2);
        assertThat(service.list(8,12)).singleElement().satisfies(variant ->
                assertThat(variant.estimate().kcalHigh()).isGreaterThan(variant.estimate().kcalLow()));
        assertThat(second.estimate().kcalHigh()).isGreaterThan(first.estimate().kcalHigh());
        assertThatThrownBy(()->service.list(8,11)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.savePersonal(8,11,new MealNutritionVariantService.Input("偷看","MALATANG",base.selections())))
                .isInstanceOf(RuntimeException.class);
        var profiles=mock(DietaryProfileService.class);
        when(profiles.get(7)).thenReturn(new DietaryProfileService.Profile(java.util.List.of("芝麻"),java.util.List.of(),
                java.util.List.of(),"NEVER","COMPLETED",""));
        service.setDietaryProfiles(profiles);
        assertThatThrownBy(()->service.savePersonal(7,11,new MealNutritionVariantService.Input("错误搭配","MALATANG",
                second.selections()))).isInstanceOf(RuntimeException.class);
    }
}

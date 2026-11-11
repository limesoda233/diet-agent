package com.diet.service.memory;

import com.diet.model.SlotBundle;
import com.diet.service.meal.constraint.MealConstraints;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanningMemorySnapshotTest extends MemoryDatabaseFixture {
    @Test void snapshotDetectsItemChangeEvenWithoutProfileEpochChange() {
        var before=store.planningSnapshot(1);
        jdbc.update("INSERT INTO user_memory VALUES ('m1',1,'AVOID_INGREDIENT','花生','DISLIKE','ACTIVE','EXPLICIT','不吃花生',NULL,1,1,NULL,1)");
        var after=store.planningSnapshot(1);
        assertNotEquals(before,after);
        var frozen=new MemoryPolicy(store).mealConstraints(after.items(),MealConstraints.empty());
        jdbc.update("UPDATE user_memory SET memory_value='鸡蛋',version=2 WHERE id='m1'");
        assertEquals(List.of("花生"),frozen.excludedIngredients());
        assertNotEquals(after,store.planningSnapshot(1));
    }
    @Test void frozenAllergenBlocksEvenAfterLiveItemDeleted() {
        jdbc.update("INSERT INTO user_memory_profile VALUES (1,TRUE,0)");
        jdbc.update("INSERT INTO user_memory VALUES ('m1',1,'ALLERGEN','花生','AVOID','PENDING','EXPLICIT','花生过敏',NULL,1,1,NULL,1)");
        var frozen=store.planningSnapshot(1);
        jdbc.update("DELETE FROM user_memory WHERE id='m1'");
        assertThrows(IllegalArgumentException.class,()->new MemoryPolicy(store).mealConstraints(frozen.items(),MealConstraints.empty()));
    }
}

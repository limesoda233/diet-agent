package com.diet.service.memory;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MemoryContextTest extends MemoryDatabaseFixture {
    @Test void restrictionsRemainAvailableWhenTheRecipeRouteIsSelectedLater() {
        var turn=new MemoryTurn(List.of(new MemoryFact("AVOID_INGREDIENT","花生","NEUTRAL","TEMPORARY","ADD","别放花生")),List.of(),List.of(),false);
        var request=new ChatRequest("s1","给个做法，别放花生",SourceMode.PUBLIC,Map.of("_memoryExcluded",List.of("鸡蛋")));
        var prepared=MemoryRequestContext.prepare(request,turn,false);
        assertThat(prepared.context()).doesNotContainKey("recipeQuery");
        var forced=new LinkedHashMap<>(prepared.context());forced.put("recipeQuery",Map.of("query","宫保鸡丁怎么做"));
        var merged=MemoryRequestContext.mergeRecipe(forced,request.message());
        assertThat(((Map<?,?>)merged.get("recipeQuery")).get("excludedIngredients")).isEqualTo(List.of("花生"));
        assertThat(MemoryRequestContext.exclusions(merged)).containsExactly("花生");
    }
    @Test void dislikeIsASoftRankingPenaltyAndCurrentRequestOverridesIt() {
        var job=store.appendTurn(1L,"s1","我一直不喜欢辣","t1");
        store.remember(job,"TASTE","辣","DISLIKE","EXPLICIT","我一直不喜欢辣",false);
        var bland=new MealItem(1L,SourceMode.PUBLIC,null,"粥",SlotBundle.empty(),.5);
        var spicySlots=new SlotBundle(List.of(),List.of(),List.of(),List.of(),List.of(),List.of("辣"),List.of());
        var spicy=new MealItem(2L,SourceMode.PUBLIC,null,"辣菜",spicySlots,.5);
        var policy=new MemoryPolicy(store);
        assertThat(policy.rank(1L,SlotBundle.empty(),List.of(spicy,bland))).extracting(MealItem::id).containsExactly(1L,2L);
        assertThat(policy.rank(1L,spicySlots,List.of(spicy,bland))).extracting(MealItem::id).containsExactly(2L,1L);
    }
    @Test void inferredPreferencesNeverBecomeSqlRecallSlots() {
        var job=store.appendTurn(1L,"s1","今天吃川菜","t1");
        store.remember(job,"CUISINE","川菜","LIKE","INFERRED","今天吃川菜",true);
        var policy=new MemoryPolicy(store);
        assertThat(policy.defaults(1L,SlotBundle.empty()).cuisine()).isEmpty();
    }
}

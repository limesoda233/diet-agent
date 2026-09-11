package com.diet.service.retrieval;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RetrievalContextTest {
    private final ConstraintParser parser=new ConstraintParser(new IngredientCatalog());
    @Test void preservesSemanticAdjustmentsWhenRequestingAnotherBatch() {
        var first=parser.resolveContext("晚餐随便来点",RetrievalContext.empty(),null,false);
        var adjusted=parser.resolveContext("换成酥脆口感的",first,null,true);
        var again=parser.resolveContext("换一批",adjusted,null,true);
        assertThat(again.queryText()).isEqualTo(adjusted.queryText()).contains("酥脆");
    }
    @Test void incompleteClarificationCannotSilentlyRemoveAnUnresolvedRestriction() {
        var pending=RetrievalContext.empty().pending("必须含西虹柿");
        assertThatThrownBy(()->parser.resolveContext("西红柿",pending,null,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.resolveContext("另外不要牛肉",pending,null,true)).isInstanceOf(IllegalArgumentException.class);
        var resolved=parser.resolveContext("确认全部限制：必须含西红柿",pending,null,true);
        assertThat(resolved.constraints().requiredIngredients()).containsExactly("番茄");
        assertThat(resolved.pendingConstraintText()).isEmpty();
        assertThat(parser.resolveContext("清空",pending,Map.of("retrievalConstraints",Map.of()),true).constraints().isEmpty()).isTrue();
    }
}

package com.diet.service.memory;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MemoryFeedbackTest extends MemoryDatabaseFixture {
    @Test void feedbackMustReferToAnItemActuallyExposedToThatUserAndTurn() {
        var job=store.appendTurn(1L,"s1","今晚吃什么","t1");
        var card=new MealResponse(10L,SourceMode.PUBLIC,"青菜",List.of("晚餐"),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),0);
        store.recordExposure(1L,"s1","t1",List.of(card));
        store.recordFeedback(1L,new FeedbackRequest("s1",10L,"LIKE",null,null,"t1"));
        store.recordFeedback(1L,new FeedbackRequest("s1",10L,"LIKE",null,null,"t1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_memory_feedback",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->store.recordFeedback(2L,new FeedbackRequest("s2",10L,"LIKE",null,null,"t1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->store.recordFeedback(1L,new FeedbackRequest("s1",11L,"LIKE",null,null,"t1"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.consolidate(0,2)).isZero(); // A meal-like does not assert liking every ingredient/tag.
        assertThat(store.list(1L)).isEmpty();
    }
}

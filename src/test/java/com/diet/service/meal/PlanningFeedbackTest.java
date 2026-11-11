package com.diet.service.meal;
import com.diet.service.feedback.FeedbackService;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PlanningFeedbackTest {
    @Test void plannedFeedbackCannotSilentlyFallBackToLegacyWhenPlanningIsDisabled() {
        var mapper=mock(FeedbackMapper.class);
        when(mapper.ownsSession(1L,"s1")).thenReturn(1);
        when(mapper.canReferenceItem(1L,3L)).thenReturn(1);
        when(mapper.ownsTrace(1L,"t1")).thenReturn(1);
        var service=new FeedbackService(mapper);
        var planned=new FeedbackRequest("s1",3L,"LIKE",5,null,"t1").planId("p1").planVersion(1).taskId("d0-dinner");
        assertThrows(IllegalArgumentException.class,()->service.save(1L,planned));
        assertDoesNotThrow(()->service.save(1L,new FeedbackRequest("s1",3L,"LIKE",5,null,"t1")));
    }
}

package com.diet.service.feedback;

import com.diet.exception.DietException;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class FeedbackOwnershipTest {
    @Test void foreignSessionOrMealCannotCreateFeedback() {
        FeedbackMapper mapper = mock(FeedbackMapper.class);
        FeedbackService service = new FeedbackService(mapper);
        FeedbackRequest request = new FeedbackRequest("foreign", 4L, "LIKE", 5, null);
        assertThrows(DietException.class, () -> service.save(10L, request));
        verify(mapper, never()).insert(any(), any(), any(), any(), any(), any());

        when(mapper.ownsSession(10L, "foreign")).thenReturn(1);
        assertThrows(DietException.class, () -> service.save(10L, request));
        verify(mapper, never()).insert(any(), any(), any(), any(), any(), any());
    }
}

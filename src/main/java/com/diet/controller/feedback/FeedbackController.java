package com.diet.controller.feedback;

import com.diet.auth.AuthPrincipal;
import com.diet.model.FeedbackRequest;
import com.diet.service.feedback.FeedbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/diet/feedback")
public class FeedbackController {
    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public void save(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody FeedbackRequest request
    ) {
        feedbackService.save(principal.userId(), request);
    }
}





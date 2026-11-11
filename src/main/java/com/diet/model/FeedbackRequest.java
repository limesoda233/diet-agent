package com.diet.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class FeedbackRequest {
    private String sessionId;
    private Long itemId;
    private String action;
    private Integer rating;
    private String reason;
    private String traceId;
    private String planId;
    private Integer planVersion;
    private String taskId;

    public FeedbackRequest(String sessionId,Long itemId,String action,Integer rating,String reason,String traceId) {
        this(sessionId,itemId,action,rating,reason,traceId,null,null,null);
    }

    public FeedbackRequest(String sessionId,Long itemId,String action,Integer rating,String reason) {
        this(sessionId,itemId,action,rating,reason,null);
    }
}

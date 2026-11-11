package com.diet.service.feedback;

import com.diet.exception.DietException;
import com.diet.mapper.FeedbackMapper;
import com.diet.model.FeedbackRequest;
import org.springframework.stereotype.Service;

@Service
public class FeedbackService {
    private com.diet.service.meal.planning.runtime.PlanningRecordStore planningStore;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setPlanningStore(com.diet.service.meal.planning.runtime.PlanningRecordStore store) {this.planningStore=store;}
    private com.diet.service.memory.MemoryStore memoryStore;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureMemory(com.diet.service.memory.MemoryService enabledService,com.diet.service.memory.MemoryStore store) {this.memoryStore=store;}
    private final FeedbackMapper feedbackMapper;

    public FeedbackService(FeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    @org.springframework.transaction.annotation.Transactional
    public void save(Long userId, FeedbackRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            throw new DietException("反馈 sessionId 不能为空");
        }
        if (request.action() == null || request.action().isBlank()) {
            throw new DietException("反馈 action 不能为空");
        }
        if (feedbackMapper.ownsSession(userId, request.sessionId()) != 1
                || request.itemId() != null && feedbackMapper.canReferenceItem(userId, request.itemId()) != 1
                || request.traceId() != null && feedbackMapper.ownsTrace(userId, request.traceId()) != 1) {
            throw new DietException("资源不存在");
        }
        if(request.planId()!=null||request.planVersion()!=null||request.taskId()!=null || planningStore!=null&&planningStore.requiresPlanIdentity(userId,request.sessionId(),request.traceId())) {
            if(planningStore==null)throw new IllegalArgumentException("计划反馈暂不可用");
            planningStore.recordFeedback(userId,request);
        }
        if(memoryStore!=null) {
            try {memoryStore.recordFeedback(userId,request);}
            catch(IllegalArgumentException invalid) {throw new DietException(invalid.getMessage());}
        }
        feedbackMapper.insert(
                userId,
                request.sessionId(),
                request.itemId(),
                request.action(),
                request.rating(),
                request.reason()
        );
    }
}

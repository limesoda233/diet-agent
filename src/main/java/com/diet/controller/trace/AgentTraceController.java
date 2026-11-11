package com.diet.controller.trace;

import com.diet.auth.AuthPrincipal;
import com.diet.model.RequestTraceRow;
import com.diet.model.TraceLabelRequest;
import com.diet.service.trace.AgentTraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/diet/debug")
public class AgentTraceController {
    private final AgentTraceService agentTraceService;

    public AgentTraceController(AgentTraceService agentTraceService) {
        this.agentTraceService = agentTraceService;
    }

    @GetMapping("/traces/{traceId}")
    public RequestTraceRow findByTraceId(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String traceId
    ) {
        return agentTraceService.findByTraceId(principal.userId(), traceId);
    }

    @GetMapping("/sessions/{sessionId}/traces")
    public List<RequestTraceRow> findBySessionId(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String sessionId,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return agentTraceService.findBySessionId(principal.userId(), sessionId, limit);
    }

    @GetMapping("/traces")
    public List<RequestTraceRow> findByTimeRange(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endAt,
            @RequestParam(value = "onlyUnlabeled", required = false) Boolean onlyUnlabeled,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return agentTraceService.findByTimeRange(principal.userId(), startAt, endAt, onlyUnlabeled, limit);
    }

    @PutMapping("/traces/{traceId}/label")
    public void updateLabel(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String traceId,
            @RequestBody TraceLabelRequest request
    ) {
        agentTraceService.updateLabel(principal.userId(), traceId, request);
    }
}





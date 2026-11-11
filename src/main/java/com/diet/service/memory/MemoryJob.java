package com.diet.service.memory;

public record MemoryJob(long id, long userId, String sessionId, long messageId,
                        String traceId, long epoch, String message, int attempts) { }

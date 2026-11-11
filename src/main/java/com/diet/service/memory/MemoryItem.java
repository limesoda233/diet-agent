package com.diet.service.memory;

public record MemoryItem(String id, long userId, String type, String value, String stance,
                         String status, String sourceType, String evidence, Long sourceMessageId,
                         long lastJobId, long version, Long expiresAt, long updatedAt) { }

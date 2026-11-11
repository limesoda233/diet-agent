package com.diet.service.memory.extraction;

public record MemoryCandidate(String type, String value, String subject, String stance,
        String scope, String operation, String evidence) { }

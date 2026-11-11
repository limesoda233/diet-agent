package com.diet.service.memory;

import com.diet.service.memory.extraction.MemoryExtractor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="diet.memory.enabled",havingValue="true")
public class MemoryConfiguration {
    @Bean MemoryService memoryService(MemoryStore store,MemoryExtractor extractor) { return new MemoryService(store,extractor); }
    @Bean MemoryPolicy memoryPolicy(MemoryStore store) { return new MemoryPolicy(store); }
    @Bean MemoryWorker memoryWorker(MemoryStore store,MemoryService service) { return new MemoryWorker(store,service); }
}

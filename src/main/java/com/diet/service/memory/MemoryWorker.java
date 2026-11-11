package com.diet.service.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

/** Durable pull queue. A crashed worker's lease expires; retries have a finite budget. */
public class MemoryWorker {
    private static final Logger log=LoggerFactory.getLogger(MemoryWorker.class);
    private final MemoryStore store;
    private final MemoryService service;
    @Value("${diet.memory.inference-min-sessions:3}") private int minimumSessions=3;
    public MemoryWorker(MemoryStore store,MemoryService service) {this.store=store;this.service=service;}
    @Scheduled(fixedDelayString="${diet.memory.worker-delay-ms:5000}",initialDelayString="${diet.memory.worker-initial-delay-ms:30000}")
    public void drain() {
        try {
            for(var job:store.pending(10)) {
                String token=store.claim(job.id());
                if(token!=null) service.process(job,token);
            }
        } catch(RuntimeException error) {log.warn("Memory queue unavailable: {}",error.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelayString="${diet.memory.consolidation-delay-ms:3600000}",initialDelayString="${diet.memory.consolidation-delay-ms:3600000}")
    public void consolidate() {
        try {store.consolidate(System.currentTimeMillis()-30L*24*3600*1000,minimumSessions);}
        catch(RuntimeException error) {log.warn("Memory consolidation unavailable: {}",error.getClass().getSimpleName());}
    }
}

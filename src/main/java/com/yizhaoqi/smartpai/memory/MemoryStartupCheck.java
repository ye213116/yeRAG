package com.yizhaoqi.smartpai.memory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class MemoryStartupCheck implements ApplicationRunner {
    private final MemoryStore store;
    public MemoryStartupCheck(MemoryStore store) { this.store=store; }
    @Override public void run(ApplicationArguments args) {
        try { store.verifySchema(); }
        catch (Exception e) { throw new IllegalStateException("Apply the yeRAG memory schema before starting", e); }
    }
}

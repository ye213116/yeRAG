package com.yizhaoqi.smartpai.memory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MemoryStartupCheck implements ApplicationRunner {
    private final MemoryProperties config;
    private final MemoryStore store;
    public MemoryStartupCheck(MemoryProperties config, MemoryStore store) { this.config=config; this.store=store; }
    @Override public void run(ApplicationArguments args) {
        if(config.isEnabled()) {
            try { store.verifySchema(); }
            catch (Exception e) { throw new IllegalStateException("Apply the yeRAG Markdown memory migration before enabling ai.memory.enabled", e); }
        }
    }
}

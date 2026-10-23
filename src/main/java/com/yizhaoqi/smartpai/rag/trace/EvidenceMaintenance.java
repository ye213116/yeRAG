package com.yizhaoqi.smartpai.rag.trace;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvidenceMaintenance {
    private final EvidenceStore store;
    public EvidenceMaintenance(EvidenceStore store) { this.store=store; }
    @Scheduled(fixedDelayString="${ai.rag.retention-sweep-ms:3600000}",initialDelayString="${ai.rag.retention-sweep-ms:3600000}")
    public void expire() {
        try { store.expire(); }
        catch(Exception error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("RAG evidence cleanup failed: {}",error.getClass().getSimpleName()); }
    }
}

package com.yizhaoqi.smartpai.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemoryTypes {
    private MemoryTypes() {}
    public record Turn(long id, long userId, String sessionId, String requestId, String question,
                       String answer, String status, Instant createdAt) {}
    public record Begin(Turn turn, boolean fresh, boolean switched) {}
    public record Task(long id, long turnId, String token, int attempts) {}
    public record Fact(String category, String subject, String attribute, String value,
                       String operation, double confidence, String evidence, String expiresAt) {}
    public record Change(String key, String category, String attribute, String value,
                         String operation, String evidence, Instant expiresAt) {}
    public enum MemoryType {
        PROFILE("profile.md", "Profile Memory"), PREFERENCE("preferences.md", "Preferences Memory");
        private final String fileName;
        private final String title;
        MemoryType(String fileName, String title) { this.fileName=fileName; this.title=title; }
        public String fileName() { return fileName; }
        public String title() { return title; }
    }
    public enum MemoryPatchOperation { UPSERT_SECTION, DELETE_SECTION }
    public record MemoryPatch(MemoryType target, UUID sectionId, String heading,
                              MemoryPatchOperation operation, String content, String evidence) {}
    public record SectionMetadata(UUID id, long documentId, String heading,
                                  long lastAppliedTurnSeq, boolean deleted) {}
    public record MemoryDocument(long id, long userId, MemoryType type, String content,
                                 List<SectionMetadata> sections) {}
    public record ResolvedMemoryPatch(UUID sectionId, String heading,
                                      MemoryPatchOperation operation, String content) {}
    public record Context(List<com.yizhaoqi.smartpai.agent.model.AgentMessage> messages, boolean reduced) {}
}

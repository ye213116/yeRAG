package com.yizhaoqi.smartpai.memory;

import com.yizhaoqi.smartpai.agent.model.AgentMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemoryTypes {
    private MemoryTypes() {}

    public record Turn(long id, long userId, String sessionId, String requestId, String question,
                       String answer, String status, Instant createdAt) {}

    public record Begin(Turn turn, boolean fresh, boolean switched) {}

    public record Task(long id, long turnId, String token, int attempts) {}

    public enum MemoryType {
        PROFILE("profile.md", "Profile Memory"),
        PREFERENCE("preferences.md", "Preferences Memory");

        private final String fileName;
        private final String title;

        MemoryType(String fileName, String title) {
            this.fileName = fileName;
            this.title = title;
        }

        public String fileName() { return fileName; }
        public String title() { return title; }
    }

    public enum MemoryPatchOperation { UPSERT_SECTION, DELETE_SECTION }

    public enum MemoryDecision { CREATE, EXTEND, REFINE, DELETE, RESTORE }

    public record MemorySource(long turnId, String role, String quote) {
        public MemorySource { if (role == null) role = "user"; }
        public MemorySource(long turnId,String quote) { this(turnId,"user",quote); }
    }
    public record MemoryCandidate(MemoryType target, UUID targetSectionId, String candidate,
                                  List<MemorySource> sources) {}
    public record MemoryPatch(MemoryType target, UUID sectionId, String heading,
                              MemoryPatchOperation operation, String content, String description,
                              MemoryDecision decision, String reason, Long expectedSectionVersion,
                              List<MemorySource> sources) {}

    public record SectionMetadata(UUID id, long documentId, String heading, String description,
                                  long sectionVersion, long lastAppliedTurnSeq, boolean deleted) {
        public SectionMetadata(UUID id,long documentId,String heading,long seq,boolean deleted) {
            this(id,documentId,heading,"用户记忆条目，读取正文后使用。",1,seq,deleted);
        }
    }
    public record MemoryDocument(long id, long userId, MemoryType type, String content,
                                 List<SectionMetadata> sections, long version) {
        public MemoryDocument(long id,long userId,MemoryType type,String content,List<SectionMetadata> sections) {
            this(id,userId,type,content,sections,0);
        }
    }
    public record CatalogDocument(long id,MemoryType type,long version,List<SectionMetadata> sections) {}
    public record MemoryIndex(String content,boolean abbreviated) {}
    public record DocumentVersion(long version,String content,String metadata,String changeSummary,
                                  String patches,Long triggerTurnId,Instant createdAt) {}
    public static final class MemoryVersionConflict extends RuntimeException {
        public MemoryVersionConflict() { super("memory version changed; reread and reintegrate"); }
    }

    public record ResolvedMemoryPatch(UUID sectionId, String heading,
                                      MemoryPatchOperation operation, String content) {}

    public record Context(List<AgentMessage> messages, boolean reduced) {}
}

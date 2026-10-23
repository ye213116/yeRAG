package com.yizhaoqi.smartpai.rag.trace;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.rag.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="ai.rag.evidence-backend", havingValue="local", matchIfMissing=true)
public class LocalEvidenceStore implements EvidenceStore {
    private final Path root;
    private final ObjectMapper json;
    private final RagProperties properties;
    public LocalEvidenceStore(ObjectMapper json, RagProperties properties) {
        this.json = json; this.properties = properties;
        properties.validate(); root = Path.of(properties.getEvidenceDirectory()).toAbsolutePath().normalize();
    }
    private Path path(String id) { return root.resolve(UUID.fromString(id).toString() + ".json"); }
    @Override public synchronized void save(String id, JsonNode document) throws Exception {
        Files.createDirectories(root);
        if(Files.getFileStore(root).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        byte[] bytes = json.writeValueAsBytes(document);
        if (bytes.length > properties.getMaxEvidenceBytes()) throw new IllegalStateException("EVIDENCE_SIZE_LIMIT");
        Path tmp = Files.createTempFile(root, ".evidence-", ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, path(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    @Override public JsonNode read(String id, String owner) throws Exception {
        Path file=path(id);
        if(Files.size(file)>properties.getMaxEvidenceBytes()) throw new java.io.IOException("EVIDENCE_SIZE_LIMIT");
        JsonNode result = json.readTree(file.toFile());
        if(!id.equals(result.path("runId").asText())) throw new java.io.IOException("EVIDENCE_ID_MISMATCH");
        if (!owner.equals(result.path("owner").asText())) throw new SecurityException("Evidence not found");
        if (result.path("startedAt").asLong() < Instant.now().minusSeconds(properties.getRetentionDays()*86400L).toEpochMilli())
            throw new java.io.FileNotFoundException("Evidence expired");
        return result;
    }
    @Override public synchronized void expire() throws Exception {
        if (!Files.isDirectory(root)) return;
        long before = System.currentTimeMillis() - properties.getRetentionDays()*86400000L;
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList())
                if (Files.getLastModifiedTime(file).toMillis() < before) Files.deleteIfExists(file);
        }
    }
}

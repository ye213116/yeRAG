package com.yizhaoqi.smartpai.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Personal deliverables are independent of expiring chat contexts and RAG trace artifacts. */
@Repository
public class ArtifactStore {
    public static final int MAX_CONTENT = 30_000;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public record Version(String id, int version, String title, String content,
                          List<SourceReference> sources, String createdAt) {}
    public record Summary(String id, int version, String title, int sourceCount, String updatedAt) {}
    public record Page(List<Summary> items, Integer nextOffset) {}
    public record Save(String id, int expectedVersion, String title, String content, String requestId) {}

    public ArtifactStore(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    public Version read(long userId, String id, Integer version) {
        uuid(id);
        if (version != null && version < 1) throw badRequest("无效版本");
        var rows = db.query("SELECT v.* FROM work_artifact a JOIN work_artifact_version v ON v.artifact_id=a.id "
                        + "AND v.owner_id=a.owner_id WHERE a.id=? AND a.owner_id=? AND v.version="
                        + (version == null ? "a.current_version" : "?"), this::map,
                version == null ? new Object[]{id, userId} : new Object[]{id, userId, version});
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    public List<Version> list(long userId, int offset, int limit) {
        return db.query("SELECT v.* FROM work_artifact a JOIN work_artifact_version v ON v.artifact_id=a.id "
                        + "AND v.owner_id=a.owner_id AND v.version=a.current_version WHERE a.owner_id=? "
                        + "ORDER BY a.updated_at DESC,a.id DESC LIMIT ? OFFSET ?", this::map, userId, limit, offset);
    }

    public List<Summary> versions(long userId, String id, int before) {
        read(userId, id, null);
        return db.query("SELECT * FROM work_artifact_version WHERE artifact_id=? AND owner_id=? AND version<? "
                        + "ORDER BY version DESC LIMIT 20", (r, n) -> summary(map(r, n)), id, userId, before);
    }

    public Version save(long userId, Save input, List<SourceReference> sources) {
        if (input == null) throw badRequest("缺少报告内容");
        uuid(input.requestId());
        if (input.title() == null || input.title().isBlank() || input.title().length() > 160
                || input.content() == null || input.content().isBlank() || input.content().length() > MAX_CONTENT
                || input.expectedVersion() < 0 || input.expectedVersion() == Integer.MAX_VALUE || (input.id() == null && input.expectedVersion() != 0)
                || (input.id() != null && input.expectedVersion() < 1)) throw badRequest("标题、正文或版本无效");
        if (input.id() != null) uuid(input.id());
        String sourceJson = encode(sources);
        String fingerprint = hash(encode(List.of(input.id() == null ? "" : input.id(), input.expectedVersion(),
                input.title().trim(), input.content(), sourceJson)));
        Version replay = replay(userId, input.requestId(), fingerprint);
        if (replay != null) return replay;
        try {
            return tx.execute(status -> {
                Instant now = Instant.now();
                String id = input.id() == null ? UUID.randomUUID().toString() : input.id();
                int number = input.expectedVersion() + 1;
                if (input.id() == null) {
                    db.update("INSERT INTO work_artifact(id,owner_id,current_version,updated_at) VALUES(?,?,1,?)",
                            id, userId, Timestamp.from(now));
                } else {
                    read(userId, id, null);
                    int changed = db.update("UPDATE work_artifact SET current_version=?,updated_at=? "
                                    + "WHERE id=? AND owner_id=? AND current_version=?", number, Timestamp.from(now),
                            id, userId, input.expectedVersion());
                    if (changed != 1) throw conflict("报告已更新，请重新读取后再保存；本次修改未覆盖最新版本");
                }
                db.update("INSERT INTO work_artifact_version(artifact_id,owner_id,version,title,content,sources_json,"
                                + "request_id,request_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?)", id, userId, number,
                        input.title().trim(), input.content(), sourceJson, input.requestId(), fingerprint, Timestamp.from(now));
                return new Version(id, number, input.title().trim(), input.content(), List.copyOf(sources), now.toString());
            });
        } catch (DuplicateKeyException duplicate) {
            Version result = replay(userId, input.requestId(), fingerprint);
            if (result != null) return result;
            throw conflict("报告版本冲突，请重新读取");
        }
    }

    private Version replay(long owner, String requestId, String hash) {
        var rows = db.query("SELECT * FROM work_artifact_version WHERE owner_id=? AND request_id=?", (r, n) -> {
            if (!hash.equals(r.getString("request_hash"))) throw conflict("同一保存请求不能用于不同内容");
            return map(r, n);
        }, owner, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Version map(ResultSet r, int row) throws SQLException {
        try {
            List<SourceReference> sources = json.readValue(r.getString("sources_json"), new TypeReference<>() {});
            return new Version(r.getString("artifact_id"), r.getInt("version"), r.getString("title"),
                    r.getString("content"), List.copyOf(sources), r.getTimestamp("created_at").toInstant().toString());
        } catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid artifact provenance", invalid); }
    }

    public static Summary summary(Version v) { return new Summary(v.id(), v.version(), v.title(), v.sources().size(), v.createdAt()); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static void uuid(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) throw badRequest("无效的成果或请求标识");
    }
    public static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "成果不存在或当前不可访问"); }
    public static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}

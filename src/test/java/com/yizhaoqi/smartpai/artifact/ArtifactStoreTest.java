package com.yizhaoqi.smartpai.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.SourceReference;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArtifactStoreTest {
    JdbcTemplate db;
    ArtifactStore store;
    ArtifactService service;
    HybridSearchService search;
    JdbcDataSource ds;
    final ObjectMapper json = new ObjectMapper();
    final SourceReference source = SourceReference.knowledge(1, "内部设计", "a".repeat(32), 2);

    @BeforeEach void setup() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:artifact" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(ds);
        try (var c = ds.getConnection()) { ScriptUtils.executeSqlScript(c, new FileSystemResource("docs/databases/ddl.sql")); }
        db.update("INSERT INTO users(id,username,password,role) VALUES(1,'alice','x','USER'),(2,'bob','x','ADMIN')");
        store = new ArtifactStore(db, json, new DataSourceTransactionManager(ds));
        search = mock(HybridSearchService.class);
        when(search.readDocumentWithPermission(anyString(), anyInt(), eq(1), anyString()))
                .thenReturn(List.of(mock(com.yizhaoqi.smartpai.entity.SearchResult.class)));
        service = new ArtifactService(store, search);
    }
    ArtifactStore.Save create(String content) { return new ArtifactStore.Save(null, 0, "升级报告", content, UUID.randomUUID().toString()); }

    @Test void durableVersionsOwnerIsolationAndRequestReplay() {
        var input = create("原始正文");
        var first = store.save(1, input, List.of());
        assertEquals(first.id(), store.save(1, input, List.of()).id());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM work_artifact_version", Integer.class));
        var second = store.save(1, new ArtifactStore.Save(first.id(), 1, "新标题", "修改正文", UUID.randomUUID().toString()), List.of());
        assertEquals(2, second.version());
        var restarted = new ArtifactStore(db, json, new DataSourceTransactionManager(ds));
        assertEquals("原始正文", restarted.read(1, first.id(), 1).content());
        assertEquals("修改正文", restarted.read(1, first.id(), null).content());
        assertEquals(List.of(2, 1), restarted.versions(1, first.id(), Integer.MAX_VALUE).stream().map(ArtifactStore.Summary::version).toList());
        assertTrue(store.list(2, 0, 20).isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> store.read(2, first.id(), null)).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> store.save(2, new ArtifactStore.Save(first.id(), 2, "窃取", "正文", UUID.randomUUID().toString()), List.of()));
        assertEquals(2, store.read(1, first.id(), null).version());
    }

    @Test void staleUpdatesAndReusedKeysCannotOverwriteOrLeavePartialVersions() {
        var input = create("第一版");
        var v1 = store.save(1, input, List.of());
        var changed = new ArtifactStore.Save(null, 0, input.title(), "不同正文", input.requestId());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> store.save(1, changed, List.of())).getStatusCode().value());
        store.save(1, new ArtifactStore.Save(v1.id(), 1, "第二版", "新正文", UUID.randomUUID().toString()), List.of());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> store.save(1,
                new ArtifactStore.Save(v1.id(), 1, "过时修改", "旧客户端", UUID.randomUUID().toString()), List.of())).getStatusCode().value());
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM work_artifact_version", Integer.class));
        assertEquals("新正文", store.read(1, v1.id(), null).content());
    }

    @Test void simultaneousUpdatesCommitOnlyOneNewVersionInH2() throws Exception {
        var v1 = store.save(1, create("first"), List.of());
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var work = (java.util.concurrent.Callable<Integer>) () -> {
                ready.countDown(); go.await();
                try {
                    return store.save(1, new ArtifactStore.Save(v1.id(), 1, "竞争写入", UUID.randomUUID().toString(), UUID.randomUUID().toString()), List.of()).version();
                } catch (ResponseStatusException conflict) { return conflict.getStatusCode().value(); }
            };
            var a = pool.submit(work); var b = pool.submit(work);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertEquals(java.util.Set.of(2, 409), java.util.Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
            assertEquals(2, store.read(1, v1.id(), null).version());
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM work_artifact_version", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void revokedKnowledgeAndSourceModesApplyToReportsAndHistory() {
        var v1 = service.save(1, create("内部结论[来源#1]"), List.of(source), SourceMode.KNOWLEDGE);
        assertThrows(ResponseStatusException.class, () -> service.read(1, v1.id(), null, SourceMode.WEB));
        assertTrue(service.list(1, 0, SourceMode.WEB).items().isEmpty());
        var web = SourceReference.web(1, "公开文档", "https://example.org/docs", "page_text");
        var v2 = service.save(1, create("公开建议[来源#1]"), List.of(web), SourceMode.WEB);
        assertThrows(ResponseStatusException.class, () -> service.read(1, v2.id(), null, SourceMode.KNOWLEDGE));
        assertEquals(2, service.list(1, 0, SourceMode.AUTO).items().size());
        when(search.readDocumentWithPermission(anyString(), anyInt(), eq(1), anyString())).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> service.read(1, v1.id(), 1, SourceMode.AUTO));
        assertThrows(ResponseStatusException.class, () -> service.versions(1, v1.id(), Integer.MAX_VALUE));
        assertEquals(List.of(v2.id()), service.list(1, 0, SourceMode.AUTO).items().stream().map(ArtifactStore.Summary::id).toList());
    }

    @Test void editsRetainProvenanceAndUnboundCitationsAreRejected() {
        var v1 = service.save(1, create("内部结论[来源#1]"), List.of(source), SourceMode.AUTO);
        var v2 = service.save(1, new ArtifactStore.Save(v1.id(), 1, "人工修改", "保留依据[来源#1]", UUID.randomUUID().toString()), List.of(), SourceMode.AUTO);
        assertEquals(List.of(source), v2.sources());
        assertThrows(ResponseStatusException.class, () -> service.save(1, create("伪造[来源#99]"), List.of(source), SourceMode.AUTO));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM work_artifact", Integer.class));
    }
}

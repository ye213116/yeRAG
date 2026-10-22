package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentToolTest {
    static final String MD5 = "a".repeat(32);
    final HybridSearchService search = mock(HybridSearchService.class);
    final ObjectMapper mapper = new ObjectMapper();
    final KnowledgeDocumentTool tool = new KnowledgeDocumentTool(search, mapper, new AiProperties());

    @Test void rereadsUsingAuthenticatedIdentityAndPreservesChunkReferences() throws Exception {
        when(search.readDocumentWithPermission(MD5, 8, 3, "42")).thenReturn(List.of(
                new SearchResult(MD5, 8, "完整的例外条件", 0d, "制度.md")));
        var result = tool.execute(args(""), new ToolExecutionContext("42", 5));
        assertTrue(result.success());
        assertEquals(MD5, result.references().get(6));
        assertEquals(8, result.sources().get(6).chunkId());
        assertEquals("完整的例外条件", mapper.readTree(result.content()).path("results").get(0).path("untrusted_content").asText());
        verify(search).readDocumentWithPermission(MD5, 8, 3, "42");
    }

    @Test void rejectsIdentityInjectionInvalidRangesDuplicateFieldsAndTrailingJson() {
        for (String json : List.of(args(",\"userId\":\"victim\""), args(",\"chunkCount\":6"),
                args(",\"chunkCount\":1.5"), args(",\"fileMd5\":\"" + MD5 + "\""), args("") + " {}",
                "{\"fileMd5\":\"" + MD5 + "\",\"startChunk\":-1}", "[]")) {
            assertFalse(tool.execute(json, new ToolExecutionContext("42")).success(), json);
        }
        verifyNoInteractions(search);
    }

    @Test void boundsEscapedOutputAndProvidesAContinuationWithinAnOversizedChunk() throws Exception {
        String text = "\"\\\n".repeat(5000);
        when(search.readDocumentWithPermission(MD5, 8, 3, "42")).thenReturn(List.of(new SearchResult(MD5, 8, text, 0d, "制度.md")));
        var result = tool.execute(args(""), new ToolExecutionContext("42"));
        assertTrue(result.success());
        assertTrue(result.content().length() <= 6000);
        var root = mapper.readTree(result.content());
        assertTrue(root.path("results").get(0).path("truncated").asBoolean());
        int next = root.path("next_offset").asInt();
        assertTrue(next > 0 && next < text.length());
        assertEquals(8, root.path("next_start_chunk").asInt());
        var continued = mapper.readTree(tool.execute(args(",\"offset\":" + next), new ToolExecutionContext("42")).content());
        assertTrue(text.substring(next).startsWith(continued.path("results").get(0).path("untrusted_content").asText()));
    }

    @Test void emptyAndUnavailableDocumentsDoNotLeakWhetherUnauthorizedDocumentExists() throws Exception {
        when(search.readDocumentWithPermission(MD5, 8, 3, "42")).thenReturn(List.of());
        assertEquals(0, tool.execute(args(""), new ToolExecutionContext("42")).resultCount());
        when(search.readDocumentWithPermission(MD5, 8, 3, "42")).thenThrow(new IllegalStateException("private diagnostic"));
        var result = tool.execute(args(""), new ToolExecutionContext("42"));
        assertFalse(result.success());
        assertFalse(result.content().contains("private diagnostic"));
    }

    private String args(String extra) { return "{\"fileMd5\":\"" + MD5 + "\",\"startChunk\":8" + extra + "}"; }
}

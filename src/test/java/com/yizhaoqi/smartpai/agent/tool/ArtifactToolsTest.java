package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.agent.SourceMode;
import com.yizhaoqi.smartpai.agent.SourceReference;
import com.yizhaoqi.smartpai.artifact.ArtifactService;
import com.yizhaoqi.smartpai.artifact.ArtifactStore;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArtifactToolsTest {
    final ObjectMapper json = new ObjectMapper();
    final ArtifactService service = mock(ArtifactService.class);
    final AiProperties config = new AiProperties();
    final String id = UUID.randomUUID().toString();
    final SourceReference source = SourceReference.web(1, "公开依据", "https://example.org/guide", "page_text");
    final ToolExecutionContext context = new ToolExecutionContext("1", 0, "call-1", "conversation", SourceMode.WEB, Map.of(1, source));

    @Test void strictArgumentsAndAuthenticatedIdentity() {
        var save = new SaveArtifactTool(service, json, config);
        for (String bad : List.of("[]", "{\"title\":\"a\",\"content\":\"b\",\"userId\":2}",
                "{\"title\":\"a\",\"title\":\"b\",\"content\":\"c\"}", "{\"title\":\"a\",\"content\":\"b\"} {}",
                "{\"title\":\"a\",\"content\":\"b\",\"expectedVersion\":1.2}",
                "{\"title\":\"a\",\"content\":\"b\",\"artifactId\":\""+id+"\"}")) {
            assertFalse(save.execute(bad, context).success(), bad);
        }
        assertFalse(save.execute("{}", new ToolExecutionContext("alice")).success());
        assertFalse(save.execute("{}", null).success());
        verifyNoInteractions(service);
    }

    @Test void longReportUsesTrustedProvenanceAndStableSaveKey() throws Exception {
        var save = new SaveArtifactTool(service, json, config);
        when(service.save(eq(1L), any(), eq(List.of(source)), eq(SourceMode.WEB)))
                .thenAnswer(call -> { var input = call.getArgument(1, ArtifactStore.Save.class);
                    return new ArtifactStore.Version(id, 1, input.title(), input.content(), List.of(source), "2026-11-19T00:00:00Z"); });
        String body = json.writeValueAsString(Map.of("title", "报告", "content", "正文".repeat(6000)));
        assertTrue(save.execute(body, context).success());
        assertTrue(save.execute(body, context).success());
        var inputs = org.mockito.ArgumentCaptor.forClass(ArtifactStore.Save.class);
        verify(service, times(2)).save(eq(1L), inputs.capture(), eq(List.of(source)), eq(SourceMode.WEB));
        assertEquals(inputs.getAllValues().get(0).requestId(), inputs.getAllValues().get(1).requestId());
        assertEquals(12000, inputs.getValue().content().length());
        assertEquals(0, save.execute(body, context).resultCount());
    }

    @Test void reportPagingKeepsCitationNumbersStableAcrossContinuationReads() throws Exception {
        var read = new ReadArtifactTool(service, json, config);
        config.getAgent().setMaxToolResultCharacters(6000);
        String original = "开头[来源#1]" + "内容".repeat(1500);
        when(service.read(1, id, null, SourceMode.AUTO)).thenReturn(new ArtifactStore.Version(id, 1, "报告", original, List.of(source), "now"));
        var first = read.execute(json.writeValueAsString(Map.of("artifactId", id, "length", 1000)),
                new ToolExecutionContext("1", 9, "read-1", "conversation"));
        assertTrue(first.success());
        assertEquals(1, first.resultCount());
        assertTrue(json.readTree(first.content()).path("truncated").asBoolean());
        StringBuilder body = new StringBuilder(json.readTree(first.content()).path("untrusted_content").asText());
        int offset = json.readTree(first.content()).path("nextOffset").asInt();
        while (offset < original.length() + 1) {
            var next = read.execute(json.writeValueAsString(Map.of("artifactId", id, "length", 1000, "offset", offset)),
                    new ToolExecutionContext("1", 10, "read-next", "conversation", SourceMode.AUTO, first.sources()));
            assertTrue(next.success()); assertEquals(0, next.resultCount());
            var page = json.readTree(next.content());
            body.append(page.path("untrusted_content").asText());
            int end = page.path("nextOffset").asInt();
            assertTrue(end > offset); offset = end;
        }
        assertEquals(original.replace("[来源#1]", "[来源#10]"), body.toString());
        assertTrue(first.content().length() <= 6000);
    }

    @Test void artifactToolsAreAvailableInEachSourceModeButPassModeToService() {
        var list = new ListArtifactsTool(service, json, config);
        for (var mode : SourceMode.values()) assertTrue(mode.allows(list));
        when(service.list(1, 0, SourceMode.KNOWLEDGE)).thenReturn(new ArtifactStore.Page(List.of(), null));
        assertTrue(list.execute("{}", new ToolExecutionContext("1", 0, "list", "conversation", SourceMode.KNOWLEDGE, Map.of())).success());
        verify(service).list(1, 0, SourceMode.KNOWLEDGE);
    }
}

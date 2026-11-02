package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.memory.MemoryProperties;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import com.yizhaoqi.smartpai.rag.RagProperties;
import com.yizhaoqi.smartpai.rag.TokenBudget;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** User-scoped tools. The server supplies identity; cursors never carry authority. */
public abstract class PersonalMemoryTool implements AgentTool {
    protected final MemoryStore store;
    protected final ObjectMapper json;
    protected final MemoryProperties config;
    protected final TokenBudget.Counter tokens;
    protected PersonalMemoryTool(MemoryStore store,ObjectMapper json,MemoryProperties config,RagProperties rag) {
        this.store=store;this.json=json;this.config=config;this.tokens=TokenBudget.counter(rag.getTokenizerResource());
    }
    @Override public Source source() { return Source.MEMORY; }
    protected long owner(ToolExecutionContext context) {
        if(!config.isRecallEnabled()) throw new IllegalArgumentException("personal memory recall is disabled");
        if(context==null || context.userId()==null) throw new IllegalArgumentException("authenticated user is required");
        try { long user=Long.parseLong(context.userId()); if(user<=0) throw new NumberFormatException(); return user; }
        catch(NumberFormatException invalid) { throw new IllegalArgumentException("invalid authenticated user"); }
    }
    public static MemoryType documentType(String value) {
        return switch(value) {
            case "PROFILE","profile.md" -> MemoryType.PROFILE;
            case "PREFERENCE","preferences.md" -> MemoryType.PREFERENCE;
            default -> throw new IllegalArgumentException("unsupported documentType; project memory requires a server-bound project identity");
        };
    }
    protected String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch(Exception invalid) { throw new IllegalArgumentException("invalid memory data",invalid); }
    }
    protected String cursor(Object value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encode(value).getBytes(StandardCharsets.UTF_8));
    }
    protected JsonNode parseCursor(String value,Set<String> fields) {
        if(value.length()>8192) throw new IllegalArgumentException("cursor is too large");
        try { return ToolArguments.parse(json,new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8),fields); }
        catch(IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid cursor",invalid); }
    }
    protected boolean fits(Object value) { return tokens.count(encode(value))<=config.getReadTokens(); }
    protected ToolExecutionResult result(Object value) {
        if(!fits(value)) throw new IllegalArgumentException("memory output exceeds budget");
        return new ToolExecutionResult(true,encode(value),0,Map.of());
    }
    protected ToolExecutionResult failure(Exception error) {
        if(error instanceof MemoryVersionConflict) return ToolExecutionResult.error("memory_version_changed","memory changed; restart the read/list without cursor");
        if(error instanceof org.springframework.web.server.ResponseStatusException) return ToolExecutionResult.error("memory_not_found","memory is unavailable for this user");
        if(error instanceof IllegalArgumentException) return ToolExecutionResult.error("tool_arguments_invalid",error.getMessage());
        return ToolExecutionResult.error("memory_unavailable","personal memory is temporarily unavailable; do not infer its contents from the index");
    }
}

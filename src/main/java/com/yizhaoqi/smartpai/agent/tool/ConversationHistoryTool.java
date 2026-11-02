package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.memory.MemoryProperties;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import com.yizhaoqi.smartpai.memory.MemoryTypes.Turn;
import com.yizhaoqi.smartpai.rag.RagProperties;
import com.yizhaoqi.smartpai.rag.TokenBudget;
import java.util.Map;

/** Shared authentication and serialized-output budget for current-conversation history tools. */
public abstract class ConversationHistoryTool implements AgentTool {
    protected final MemoryStore store;
    protected final ObjectMapper json;
    protected final MemoryProperties config;
    protected final TokenBudget.Counter tokens;
    protected ConversationHistoryTool(MemoryStore store,ObjectMapper json,MemoryProperties config,RagProperties rag) {
        this.store=store; this.json=json; this.config=config; this.tokens=TokenBudget.counter(rag.getTokenizerResource());
    }
    protected long owner(ToolExecutionContext context) {
        if (context==null || context.userId()==null || context.conversationId()==null || context.conversationId().isBlank())
            throw new IllegalArgumentException("authenticated conversation context is required");
        try {
            long user=Long.parseLong(context.userId());
            if (user<=0) throw new NumberFormatException();
            store.session(user,context.conversationId());
            return user;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid authenticated user"); }
    }
    protected Map<String,Object> item(Turn turn,String role,String content,int offset,boolean truncated) {
        return Map.of("turnId",turn.id(),"role",role,"time",turn.createdAt().toString(),
                "offset",offset,"untrusted_content",content,"truncated",truncated);
    }
    protected String encode(Object result) {
        try { return json.writeValueAsString(result); }
        catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    protected boolean fits(Object result) { return tokens.count(encode(result))<=config.getHistoryToolTokens(); }
    protected ToolExecutionResult result(long user,String session,Object result) {
        store.session(user,session); // Recheck after the query and serialization, including deletion.
        String content=encode(result);
        if (tokens.count(content)>config.getHistoryToolTokens()) throw new IllegalStateException("history output exceeds budget");
        return new ToolExecutionResult(true,content,0,Map.of());
    }
    protected ToolExecutionResult failure(Exception error) {
        if (error instanceof org.springframework.web.server.ResponseStatusException)
            return ToolExecutionResult.error("history_not_available","conversation or turn is unavailable");
        if (error instanceof IllegalArgumentException)
            return ToolExecutionResult.error("tool_arguments_invalid",error.getMessage());
        return ToolExecutionResult.error("history_unavailable","conversation history is temporarily unavailable");
    }
}

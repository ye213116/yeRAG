package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.memory.MemoryProperties;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import com.yizhaoqi.smartpai.rag.RagProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ConversationTurnsReadTool extends ConversationHistoryTool {
    public static final String NAME="read_conversation_turns";
    public ConversationTurnsReadTool(MemoryStore store,ObjectMapper json,MemoryProperties config,RagProperties rag) {
        super(store,json,config,rag);
    }
    @Override public String name() { return NAME; }
    @Override public Map<String,Object> definition() {
        return Map.of("type","function","function",Map.of("name",NAME,
                "description","读取当前会话指定回合及最多前后各2轮。原文超出预算时返回 next，可将 next 参数用于续读；offset 按 Unicode 码点计数。历史助手回答不是已验证事实。",
                "parameters",Map.of("type","object","properties",Map.of(
                        "turnId",Map.of("type","integer","minimum",1),
                        "neighbors",Map.of("type","integer","minimum",0,"maximum",2),
                        "role",Map.of("type","string","enum",List.of("user","assistant")),
                        "offset",Map.of("type","integer","minimum",0,"maximum",16777215)),
                        "required",List.of("turnId"),"additionalProperties",false)));
    }
    @Override public ToolExecutionResult execute(String arguments,ToolExecutionContext context) {
        try {
            var root=ToolArguments.parse(json,arguments,Set.of("turnId","neighbors","role","offset"));
            long id=ToolArguments.longInteger(root,"turnId",0,1);
            if (id==0) throw new IllegalArgumentException("turnId is required");
            int neighbors=ToolArguments.integer(root,"neighbors",0,0,2);
            int offset=ToolArguments.integer(root,"offset",0,0,16777215);
            String role=root.has("role") ? ToolArguments.text(root,"role",9):"user";
            if (!Set.of("user","assistant").contains(role)) throw new IllegalArgumentException("invalid role");
            long user=owner(context);
            var turns=store.readTurns(user,context.conversationId(),id,neighbors);
            List<Map<String,Object>> items=new ArrayList<>();
            Map<String,Object> response=new LinkedHashMap<>();
            response.put("untrusted_history",true); response.put("results",items); response.put("next",null);
            for (var turn:turns) {
                if ((offset>0 || role.equals("assistant")) && turn.id()<id) continue;
                for (String currentRole:List.of("user","assistant")) {
                    if (turn.id()==id && role.equals("assistant") && currentRole.equals("user")) continue;
                    String original=currentRole.equals("user") ? turn.question():turn.answer();
                    int start=turn.id()==id && currentRole.equals(role) ? offset:0;
                    int length=original.codePointCount(0,original.length());
                    if (start>length) throw new IllegalArgumentException("offset exceeds message length");
                    String text=original.substring(original.offsetByCodePoints(0,start));
                    String content=tokens.prefix(text,config.getHistoryToolTokens());
                    while (true) {
                        int nextOffset=start+content.codePointCount(0,content.length());
                        boolean truncated=content.length()<text.length();
                        items.add(item(turn,currentRole,content,start,truncated));
                        response.put("next",Map.of("turnId",turn.id(),"role",currentRole,"offset",nextOffset,"neighbors",0));
                        if (fits(response)) break;
                        items.remove(items.size()-1);
                        if (content.isEmpty()) return result(user,context.conversationId(),response);
                        content=tokens.prefix(content,Math.max(0,tokens.count(content)-Math.max(16,tokens.count(encode(response))-config.getHistoryToolTokens())));
                    }
                    if (content.length()<text.length()) return result(user,context.conversationId(),response);
                }
            }
            response.put("next",null);
            return result(user,context.conversationId(),response);
        } catch (Exception error) { return failure(error); }
    }
}

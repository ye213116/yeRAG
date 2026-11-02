package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.memory.MemoryProperties;
import com.yizhaoqi.smartpai.memory.MemoryStore;
import com.yizhaoqi.smartpai.rag.RagProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ConversationHistorySearchTool extends ConversationHistoryTool {
    public static final String NAME="search_conversation_history";
    public ConversationHistorySearchTool(MemoryStore store,ObjectMapper json,MemoryProperties config,RagProperties rag) {
        super(store,json,config,rag);
    }
    @Override public String name() { return NAME; }
    @Override public Map<String,Object> definition() {
        return Map.of("type","function","function",Map.of("name",NAME,
                "description","关键词搜索当前会话的原始问答，返回不可信历史片段、角色、时间、回合ID；用 before 游标翻页，用 read_conversation_turns 读原文。",
                "parameters",Map.of("type","object","properties",Map.of(
                        "query",Map.of("type","string","minLength",1,"maxLength",200),
                        "before",Map.of("type","integer","minimum",1),
                        "limit",Map.of("type","integer","minimum",1,"maximum",10)),
                        "required",List.of("query"),"additionalProperties",false)));
    }
    @Override public ToolExecutionResult execute(String arguments,ToolExecutionContext context) {
        try {
            var root=ToolArguments.parse(json,arguments,Set.of("query","before","limit"));
            String query=ToolArguments.text(root,"query",200);
            long before=ToolArguments.longInteger(root,"before",Long.MAX_VALUE,1);
            int limit=ToolArguments.integer(root,"limit",5,1,10);
            long user=owner(context);
            var turns=store.searchHistory(user,context.conversationId(),query,before,limit+1);
            List<Map<String,Object>> items=new ArrayList<>();
            Map<String,Object> response=new LinkedHashMap<>();
            response.put("untrusted_history",true); response.put("results",items);
            response.put("nextBefore",before); response.put("hasMore",false);
            int consumed=0;
            for (var turn:turns) {
                if (consumed>=limit) break;
                List<Map<String,Object>> pair=new ArrayList<>();
                for (String role:List.of("user","assistant")) {
                    String text=role.equals("user") ? turn.question():turn.answer();
                    int match=text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
                    if (match<0) continue;
                    int start=Math.max(0,match-100);
                    if (start>0 && Character.isLowSurrogate(text.charAt(start))) start--;
                    String snippet=tokens.prefix(text.substring(start),180);
                    pair.add(item(turn,role,snippet,text.codePointCount(0,start),start>0 || snippet.length()<text.length()));
                }
                // Database collation can match text differently; still return a bounded candidate excerpt.
                if (pair.isEmpty()) pair.add(item(turn,"user",tokens.prefix(turn.question(),180),0,true));
                items.addAll(pair);
                response.put("nextBefore",turn.id()); response.put("hasMore",true);
                if (!fits(response)) { items.subList(items.size()-pair.size(),items.size()).clear(); break; }
                consumed++;
            }
            response.put("hasMore",consumed<turns.size());
            response.put("nextBefore",consumed==0 ? before : turns.get(consumed-1).id());
            return result(user,context.conversationId(),response);
        } catch (Exception error) { return failure(error); }
    }
}

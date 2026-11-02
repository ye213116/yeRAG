package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.memory.*;
import com.yizhaoqi.smartpai.memory.MemoryTypes.*;
import com.yizhaoqi.smartpai.rag.RagProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryReadTool extends PersonalMemoryTool {
    public static final String NAME="read_memory";
    public MemoryReadTool(MemoryStore store,ObjectMapper json,MemoryProperties config,RagProperties rag) { super(store,json,config,rag); }
    @Override public String name() { return NAME; }
    @Override public Map<String,Object> definition() {
        return Map.of("type","function","function",Map.of("name",NAME,"description","批量读取当前用户记忆正文、来源、版本。应用个人偏好或陈述用户历史事实前必须读取；索引不代表正文。用nextCursor续读，已读内容无需重复调用。",
                "parameters",Map.of("type","object","properties",Map.of("documentType",Map.of("type","string","enum",List.of("PROFILE","PREFERENCE")),
                        "sectionIds",Map.of("type","array","items",Map.of("type","string","format","uuid"),"minItems",1,"maxItems",8,"uniqueItems",true),
                        "cursor",Map.of("type","string","maxLength",8192)),"required",List.of("documentType"),"additionalProperties",false)));
    }
    @Override public ToolExecutionResult execute(String arguments,ToolExecutionContext context) {
        try {
            var root=ToolArguments.parse(json,arguments,Set.of("documentType","sectionIds","cursor"));
            var type=documentType(ToolArguments.text(root,"documentType",32));long user=owner(context);
            var doc=store.document(user,type); List<UUID> ids=new ArrayList<>(); int index=0,offset=0;
            if(root.has("cursor")) {
                if(root.has("sectionIds")) throw new IllegalArgumentException("continuation uses cursor alone");
                var page=parseCursor(ToolArguments.text(root,"cursor",8192),Set.of("version","documentType","ids","index","offset"));
                if(!type.name().equals(ToolArguments.text(page,"documentType",32))) throw new IllegalArgumentException("cursor type mismatch");
                if(ToolArguments.longInteger(page,"version",-1,0)!=doc.version()) throw new MemoryVersionConflict();
                ids=ids(page.get("ids"),config.getMaxSectionsPerDocument());
                index=ToolArguments.integer(page,"index",-1,0,ids.size()); offset=ToolArguments.integer(page,"offset",-1,0,config.getMaxSectionBytes());
            } else if(root.has("sectionIds")) ids=ids(root.get("sectionIds"),8);
            else ids=doc.sections().stream().filter(s -> !s.deleted()).map(SectionMetadata::id).toList();
            Map<UUID,SectionMetadata> metadata=new HashMap<>();doc.sections().stream().filter(s -> !s.deleted()).forEach(s -> metadata.put(s.id(),s));
            for(UUID id:ids) if(!metadata.containsKey(id)) throw new IllegalArgumentException("section is not active in this user's document");
            Map<String,String> bodies=new HashMap<>();new MemoryDocumentEditor().parse(doc.content()).sections().forEach(s -> bodies.put(s.heading(),s.content()));
            List<Map<String,Object>> items=new ArrayList<>();Map<String,Object> response=new LinkedHashMap<>();
            response.put("documentType",type);response.put("fileName",type.fileName());response.put("documentVersion",doc.version());response.put("untrusted_memory",true);response.put("sections",items);response.put("nextCursor",null);
            for(int i=index;i<ids.size();i++) {
                var section=metadata.get(ids.get(i));String original=bodies.get(section.heading());
                if(original==null) throw new IllegalStateException("memory body and metadata differ");
                int start=i==index ? offset:0;
                if(start<0 || start>original.codePointCount(0,original.length())) throw new IllegalArgumentException("cursor offset out of range");
                String text=original.substring(original.offsetByCodePoints(0,start));
                String content=tokens.prefix(text,config.getReadTokens());
                var source=boundedProvenance(store.provenance(user,type,section.id()));
                while(true) {
                    int end=start+content.codePointCount(0,content.length());boolean truncated=content.length()<text.length();
                    Map<String,Object> item=new LinkedHashMap<>();item.put("sectionId",section.id());item.put("title",section.heading());item.put("sectionVersion",section.sectionVersion());
                    item.put("offset",start);item.put("untrusted_content",content);item.put("truncated",truncated);item.put("provenance",source);items.add(item);
                    int nextIndex=truncated ? i:i+1;
                    response.put("nextCursor",nextIndex<ids.size() ? cursor(Map.of("version",doc.version(),"documentType",type.name(),"ids",ids,"index",nextIndex,"offset",truncated ? end:0)):null);
                    if(fits(response)) break;
                    items.remove(items.size()-1);
                    if(content.isEmpty()) {
                        response.put("nextCursor",cursor(Map.of("version",doc.version(),"documentType",type.name(),"ids",ids,"index",i,"offset",start)));
                        if(items.isEmpty()) throw new IllegalArgumentException("memory metadata exceeds return budget");
                        return checkedResult(user,type,doc.version(),response);
                    }
                    content=tokens.prefix(content,Math.max(0,tokens.count(content)-64));
                }
                if(content.length()<text.length()) break;
            }
            return checkedResult(user,type,doc.version(),response);
        } catch(Exception error) { return failure(error); }
    }
    private ToolExecutionResult checkedResult(long user,MemoryType type,long expected,Object response) {
        if(store.document(user,type).version()!=expected) throw new MemoryVersionConflict();
        return result(response);
    }
    private List<UUID> ids(com.fasterxml.jackson.databind.JsonNode node,int limit) {
        if(node==null || !node.isArray() || node.isEmpty() || node.size()>limit) throw new IllegalArgumentException("invalid sectionIds");
        List<UUID> result=new ArrayList<>();
        for(var value:node) {
            if(!value.isTextual()) throw new IllegalArgumentException("section ID must be a UUID string");
            UUID id=UUID.fromString(value.textValue()); if(result.contains(id)) throw new IllegalArgumentException("duplicate section ID");result.add(id);
        }
        return result;
    }
    private Object boundedProvenance(Map<String,Object> source) {
        var node=json.valueToTree(source);
        if(node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            if(object.has("reason")) object.put("reason",tokens.prefix(object.path("reason").asText(),60));
            for(var item:object.path("sources")) if(item instanceof com.fasterxml.jackson.databind.node.ObjectNode citation) {
                String quote=citation.path("quote").asText();String clipped=tokens.prefix(quote,60);
                citation.put("quote",clipped);citation.put("quoteTruncated",clipped.length()<quote.length());
            }
        }
        return node;
    }
}

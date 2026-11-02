package com.yizhaoqi.smartpai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.memory.*;
import com.yizhaoqi.smartpai.rag.RagProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryListTool extends PersonalMemoryTool {
    public static final String NAME="list_memory_sections";
    public MemoryListTool(MemoryStore store,ObjectMapper json,MemoryProperties config,RagProperties rag) { super(store,json,config,rag); }
    @Override public String name() { return NAME; }
    @Override public Map<String,Object> definition() {
        return Map.of("type","function","function",Map.of("name",NAME,"description","分页发现当前用户的记忆条目，仅返回sectionId、标题、描述和版本。描述不能替代正文；随后用read_memory批量读取。",
                "parameters",Map.of("type","object","properties",Map.of("documentType",Map.of("type","string","enum",List.of("PROFILE","PREFERENCE")),"cursor",Map.of("type","string","maxLength",8192)),
                        "required",List.of("documentType"),"additionalProperties",false)));
    }
    @Override public ToolExecutionResult execute(String arguments,ToolExecutionContext context) {
        try {
            var root=ToolArguments.parse(json,arguments,Set.of("documentType","cursor"));
            var type=documentType(ToolArguments.text(root,"documentType",32)); long user=owner(context);
            var doc=store.catalog(user).stream().filter(d -> d.type()==type).findFirst().orElse(null);
            long version=doc==null ? 0:doc.version(); int start=0;
            if(root.has("cursor")) {
                var page=parseCursor(ToolArguments.text(root,"cursor",8192),Set.of("version","index","documentType"));
                if(!type.name().equals(ToolArguments.text(page,"documentType",32))) throw new IllegalArgumentException("cursor type mismatch");
                if(ToolArguments.longInteger(page,"version",-1,0)!=version) throw new com.yizhaoqi.smartpai.memory.MemoryTypes.MemoryVersionConflict();
                start=ToolArguments.integer(page,"index",-1,0,config.getMaxSectionsPerDocument());
            }
            var sections=doc==null ? List.<com.yizhaoqi.smartpai.memory.MemoryTypes.SectionMetadata>of():doc.sections().stream().filter(s -> !s.deleted()).toList();
            if(start<0 || start>sections.size()) throw new IllegalArgumentException("cursor out of range");
            List<Map<String,Object>> items=new ArrayList<>(); Map<String,Object> response=new LinkedHashMap<>();
            response.put("documentType",type);response.put("documentVersion",version);response.put("untrusted_memory",true);response.put("sections",items);response.put("nextCursor",null);
            for(int i=start;i<sections.size();i++) {
                var section=sections.get(i);
                items.add(Map.of("sectionId",section.id(),"title",section.heading(),"description",section.description(),"version",section.sectionVersion()));
                response.put("nextCursor",i+1<sections.size() ? cursor(Map.of("version",version,"index",i+1,"documentType",type.name())):null);
                if(items.size()>20 || !fits(response)) {
                    items.remove(items.size()-1);
                    response.put("nextCursor",cursor(Map.of("version",version,"index",i,"documentType",type.name())));
                    if(items.isEmpty()) throw new IllegalArgumentException("section metadata exceeds return budget");
                    break;
                }
            }
            long latest=store.catalog(user).stream().filter(d -> d.type()==type).mapToLong(d -> d.version()).findFirst().orElse(0);
            if(latest!=version) throw new com.yizhaoqi.smartpai.memory.MemoryTypes.MemoryVersionConflict();
            return result(response);
        } catch(Exception error) { return failure(error); }
    }
}

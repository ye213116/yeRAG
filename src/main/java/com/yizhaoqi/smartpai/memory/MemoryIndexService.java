package com.yizhaoqi.smartpai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.rag.RagProperties;
import com.yizhaoqi.smartpai.rag.TokenBudget;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.memory.MemoryTypes.*;

/** Discovery contains metadata only. Descriptions never substitute for the source body. */
@Service
public class MemoryIndexService {
    private final MemoryProperties config;
    private final TokenBudget.Counter tokens;
    private final ObjectMapper json;
    public MemoryIndexService(MemoryProperties config,RagProperties rag,ObjectMapper json) {
        this.config=config; this.tokens=TokenBudget.counter(rag.getTokenizerResource()); this.json=json;
    }
    public MemoryIndex build(List<CatalogDocument> documents) {
        StringBuilder full=new StringBuilder("MEMORY.md\n# 用户记忆索引\n描述仅用于发现，应用偏好或陈述用户事实前须 read_memory 读取正文。\n");
        for(var doc:documents) {
            full.append("\n## ").append(doc.type().fileName()).append(" (documentType=").append(doc.type()).append(", version=").append(doc.version()).append(")\n");
            for(var section:doc.sections()) if(!section.deleted()) full.append(entry(section)).append('\n');
        }
        if(tokens.count(full.toString())<=config.getIndexTokens()) return new MemoryIndex(full.toString(),false);
        StringBuilder brief=new StringBuilder("MEMORY.md\n# 用户记忆索引\n索引超过预算，仅列文档概要。使用 list_memory_sections 分页发现，再用 read_memory 批量读取。描述不能替代正文。\n");
        for(var doc:documents) brief.append("- ").append(doc.type().fileName()).append(" documentType=").append(doc.type())
                .append(" version=").append(doc.version()).append(" sections=").append(doc.sections().stream().filter(s -> !s.deleted()).count()).append('\n');
        if(tokens.count(brief.toString())>config.getIndexTokens()) throw new IllegalArgumentException("document overview exceeds index budget");
        return new MemoryIndex(brief.toString(),true);
    }
    public String entry(SectionMetadata section) {
        try { return json.writeValueAsString(Map.of("sectionId",section.id(),"title",section.heading(),
                "description",section.description(),"version",section.sectionVersion())); }
        catch(Exception invalid) { throw new IllegalStateException(invalid); }
    }
    public void validateCatalog(List<CatalogDocument> documents) {
        int total=0;
        for(var doc:documents) {
            if(doc.sections().size()>config.getMaxSectionsPerDocument()) throw new IllegalArgumentException("section catalog is full");
            for(var section:doc.sections()) total+=tokens.count(entry(section));
        }
        if(total>config.getMaxCatalogTokens()) throw new IllegalArgumentException("catalog metadata exceeds storage budget");
    }
}

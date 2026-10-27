package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.util.*;

/** Map each child independently to a unique position in verified source text; never concatenate children to claim a child hit. */
public final class EvidenceSpanMapping {
    private EvidenceSpanMapping() {}
    public static ArrayNode groups(ObjectMapper json,JsonNode document,String body,String quote,List<Candidate> visible) {
        if(!EvidenceRecorder.hash(body).equals(document.path("sha256").asText())) throw new IllegalArgumentException("Changed span source document");
        String source=EvidencePositions.normalize(body),needle=EvidencePositions.normalize(quote); ArrayNode result=json.createArrayNode();
        if(needle.isEmpty()) throw new IllegalArgumentException("Empty span");
        for(int start=source.indexOf(needle);start>=0;start=source.indexOf(needle,start+1)) {
            int end=start+needle.length(); Map<String,ArrayNode> owners=new TreeMap<>();
            for(Candidate child:visible) {
                if(!child.fileMd5().equals(document.path("fileMd5").asText())) continue;
                String normalized=EvidencePositions.normalize(child.text()); int position=source.indexOf(normalized);
                // Repeated child text has ambiguous original coordinates; do not invent a location.
                if(normalized.isEmpty() || position<0 || position!=source.lastIndexOf(normalized)) continue;
                int left=Math.max(start,position),right=Math.min(end,position+normalized.length());
                if(left>=right) continue;
                String fragment=normalized.substring(left-position,right-position);
                for(var range:EvidencePositions.locate(child.text(),fragment)) {
                    if(EvidencePositions.normalize(child.text().substring(0,range.start())).length()!=left-position
                            || EvidencePositions.normalize(child.text().substring(0,range.end())).length()!=right-position) continue;
                    ObjectNode anchor=owners.computeIfAbsent(child.owner(),key -> json.createArrayNode()).addObject();
                    anchor.put("sourceId",child.id()).put("sourceHash",EvidenceRecorder.hash(child.text())).put("sourceText",child.text())
                        .put("owner",child.owner()).put("fileMd5",child.fileMd5()).put("chunkId",child.chunkIndex()).put("esIndex",child.esIndex()).put("esHitId",child.esHitId())
                        .put("start",range.start()).put("end",range.end()).put("coverageStart",left).put("coverageEnd",right);
                }
            }
            for(var anchors:owners.values()) if(EvidencePositions.covers(anchors,start,end,a -> true)) {
                if(result.size()>=32) throw new IllegalArgumentException("Too many ambiguous source-span alternatives");
                ObjectNode group=result.addObject().put("start",start).put("end",end).put("sourceDocument",document.path("id").asText())
                    .put("sourceDocumentSha256",document.path("sha256").asText()).put("coordinateSystem","NFKC_NON_WHITESPACE_SOURCE_UTF16");
                group.set("anchors",anchors);
            }
        }
        return result;
    }
}

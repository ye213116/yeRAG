package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder;
import java.text.*;
import java.util.*;

/** Exact normalized matching with a reversible mapping to original UTF-16 coordinates. */
public final class EvidencePositions {
    private EvidencePositions() {}
    public record Range(int start,int end) {}
    public static String normalize(String text) {
        StringBuilder result=new StringBuilder();
        Normalizer.normalize(text,Normalizer.Form.NFKC).codePoints()
            .filter(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)).forEach(result::appendCodePoint);
        return result.toString();
    }
    public static List<Range> locate(String text,String quote) {
        String needle=normalize(quote);
        if(needle.isBlank()) throw new IllegalArgumentException("Empty normalized evidence quote");
        StringBuilder normalized=new StringBuilder(); List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
        BreakIterator iterator=BreakIterator.getCharacterInstance(Locale.ROOT); iterator.setText(text);
        int start=iterator.first();
        for(int end=iterator.next();end!=BreakIterator.DONE;start=end,end=iterator.next()) {
            String segment=normalize(text.substring(start,end)); normalized.append(segment);
            for(int i=0;i<segment.length();i++) { starts.add(start); ends.add(end); }
        }
        if(!normalized.toString().equals(normalize(text))) throw new IllegalArgumentException("Unsupported cross-grapheme normalization; revise quote");
        List<Range> found=new ArrayList<>();
        for(int offset=normalized.indexOf(needle);offset>=0;offset=normalized.indexOf(needle,offset+1))
            found.add(new Range(starts.get(offset),ends.get(offset+needle.length()-1)));
        return List.copyOf(found);
    }
    public static ObjectNode candidates(ObjectMapper json,JsonNode units,JsonNode candidates) {
        return assess(json,units,anchor -> {
            for(JsonNode candidate:candidates) if(same(anchor,candidate) && candidate.path("text").asText().length()>=anchor.path("end").asInt()) return true;
            return false;
        });
    }
    public static ObjectNode passages(ObjectMapper json,JsonNode units,JsonNode passages) {
        return assess(json,units,anchor -> {
            List<Range> covered=new ArrayList<>();
            for(JsonNode passage:passages) for(JsonNode source:passage.path("sources")) {
                if(!anchor.path("sourceId").asText().equals(source.path("sourceId").asText()) || !anchor.path("sourceHash").asText().equals(source.path("sourceHash").asText())) continue;
                int left=source.path("sourceStart").asInt(-1),right=source.path("sourceEnd").asInt(-1);
                int outStart=source.path("outputStart").asInt(-1),outEnd=source.path("outputEnd").asInt(-1);
                String output=passage.path("text").asText();
                if(left<0 || right<left || outStart<0 || outEnd>output.length() || outEnd-outStart!=right-left) throw new IllegalStateException("Invalid passage coordinate mapping");
                String original=anchor.path("sourceText").asText();
                if(right>original.length() || !original.substring(left,right).equals(output.substring(outStart,outEnd))) throw new IllegalStateException("Passage source text mismatch");
                covered.add(new Range(left,right));
            }
            covered.sort(Comparator.comparingInt(Range::start));
            int cursor=anchor.path("start").asInt(),end=anchor.path("end").asInt();
            for(Range range:covered) if(range.start()<=cursor) cursor=Math.max(cursor,range.end());
            return cursor>=end;
        });
    }
    private static boolean same(JsonNode anchor,JsonNode candidate) {
        return anchor.path("sourceId").asText().equals(candidate.path("id").asText())
            && anchor.path("owner").asText().equals(candidate.path("owner").asText())
            && anchor.path("fileMd5").asText().equals(candidate.path("fileMd5").asText())
            && anchor.path("chunkId").asInt(-1)==candidate.path("chunkId").asInt(candidate.path("chunkIndex").asInt(-2))
            && anchor.path("sourceHash").asText().equals(EvidenceRecorder.hash(candidate.path("text").asText()));
    }
    private static ObjectNode assess(ObjectMapper json,JsonNode units,java.util.function.Predicate<JsonNode> found) {
        ObjectNode result=json.createObjectNode(); ArrayNode present=result.putArray("coveredUnits"),missing=result.putArray("missingUnits");
        for(JsonNode unit:units) {
            boolean hit=false; for(JsonNode anchor:unit.path("anchors")) if(found.test(anchor)) { hit=true; break; }
            (hit?present:missing).add(unit.path("id").asText());
        }
        int count=present.size(),total=units.size();
        result.put("covered",count).put("required",total);
        if(total==0) result.put("state","NOT_APPLICABLE");
        else result.put("state",count==0?"NONE":count==total?"FULL":"PARTIAL").put("coverage",(double)count/total).put("hit",count>0?1:0);
        return result;
    }
}

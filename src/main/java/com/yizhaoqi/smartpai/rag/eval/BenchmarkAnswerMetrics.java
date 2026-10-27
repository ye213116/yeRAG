package com.yizhaoqi.smartpai.rag.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** HotpotQA answer rules, evaluated on the original full answer; no Judge-based extraction. */
public final class BenchmarkAnswerMetrics {
    public static final String SCORER="hotpotqa-answer-v1";
    private static final String PUNCTUATION="!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
    private BenchmarkAnswerMetrics() {}
    public static String normalize(String value) {
        StringBuilder text=new StringBuilder();
        value.toLowerCase(Locale.ROOT).codePoints().filter(c -> c>127 || PUNCTUATION.indexOf(c)<0).forEach(text::appendCodePoint);
        return text.toString().replaceAll("\\b(a|an|the)\\b"," ").replaceAll("(?U)\\s+"," ").strip();
    }
    public static ObjectNode score(ObjectMapper json,String prediction,JsonNode references,boolean completed,String scorer) {
        ObjectNode result=json.createObjectNode().put("scorer",scorer).put("prediction",completed?prediction:"")
            .put("generationComplete",completed).put("answerSurface","FULL_GENERATED_ANSWER");
        if(!SCORER.equals(scorer)) return result.put("status","NOT_APPLICABLE").put("reason","NO_TASK_SPECIFIC_SCORER");
        double em=0,f1=0;
        if(completed) for(JsonNode reference:references) {
            String guess=normalize(prediction),gold=normalize(reference.asText());
            em=Math.max(em,guess.equals(gold)?1:0);
            if((Set.of("yes","no","noanswer").contains(guess) || Set.of("yes","no","noanswer").contains(gold)) && !guess.equals(gold)) continue;
            List<String> a=guess.isEmpty()?List.of():Arrays.asList(guess.split(" "));
            List<String> b=gold.isEmpty()?List.of():Arrays.asList(gold.split(" "));
            Map<String,Integer> counts=new HashMap<>(); for(String token:b) counts.merge(token,1,Integer::sum);
            int common=0;
            for(String token:a) if(counts.getOrDefault(token,0)>0) { common++; counts.merge(token,-1,Integer::sum); }
            if(common>0) f1=Math.max(f1,2.0*common/(a.size()+b.size()));
        }
        return result.put("status","SCORED").put("em",em).put("f1",f1);
    }
}

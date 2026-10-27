package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yizhaoqi.smartpai.rag.eval.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationSupportJudgeTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode assessment() {
        ObjectNode value=json.createObjectNode().put("extractionComplete",true).put("reason","synthetic test judgment");
        ObjectNode claim=value.putArray("claims").addObject().put("claim","Alice is in Paris").put("answerQuote","Paris").put("verdict","supported").put("reason","synthetic entailment");
        claim.putArray("contextQuotes").add("Alice is in Paris."); value.putArray("keyPoints"); return value;
    }
    @Test void validQuotesProduceIndependentSupportButMissingKeyPointsStayNotApplicable() throws Exception {
        JsonNode valid=EvaluationSupportJudge.parse(json,assessment().toString(),"Paris","Alice is in Paris.",json.createArrayNode());
        var metrics=EvaluationSupportJudge.metrics(json,valid);
        assertEquals(1,metrics.path("judgeClaimSupportRate").asDouble()); assertFalse(metrics.has("judgeAnswerCompleteness"));
        assertEquals("NOT_APPLICABLE",metrics.path("completenessStatus").asText());
    }
    @Test void fabricatedQuotesAndDuplicateClaimsAndTrailingJsonAreRejected() {
        ObjectNode invented=assessment(); ((ArrayNode)invented.path("claims").path(0).path("contextQuotes")).removeAll().add("invented evidence");
        assertThrows(Exception.class,() -> EvaluationSupportJudge.parse(json,invented.toString(),"Paris","Alice is in Paris.",json.createArrayNode()));
        ObjectNode duplicate=assessment(); ((ArrayNode)duplicate.get("claims")).add(duplicate.path("claims").path(0).deepCopy());
        assertThrows(Exception.class,() -> EvaluationSupportJudge.parse(json,duplicate.toString(),"Paris","Alice is in Paris.",json.createArrayNode()));
        assertThrows(Exception.class,() -> EvaluationSupportJudge.parse(json,assessment()+" {}","Paris","Alice is in Paris.",json.createArrayNode()));
    }
    @Test void EmptyOrIncompleteClaimExtractionNeverGetsPerfectSupport() {
        ObjectNode empty=assessment(); empty.putArray("claims");
        assertFalse(EvaluationSupportJudge.metrics(json,empty).has("judgeClaimSupportRate"));
        ObjectNode incomplete=assessment().put("extractionComplete",false);
        assertFalse(EvaluationSupportJudge.metrics(json,incomplete).has("judgeClaimSupportRate"));
    }
    @Test void CompletenessRequiresOneValidAnswerMappingPerRealKeyPoint() throws Exception {
        ObjectNode value=assessment(); value.putArray("keyPoints").addObject().put("index",0).put("covered",true).put("answerQuote","Paris").put("reason","synthetic completeness");
        JsonNode points=json.createArrayNode().add("location");
        JsonNode valid=EvaluationSupportJudge.parse(json,value.toString(),"Paris","Alice is in Paris.",points);
        assertEquals(1,EvaluationSupportJudge.metrics(json,valid).path("judgeAnswerCompleteness").asDouble());
        ((ObjectNode)value.path("keyPoints").path(0)).put("index",1);
        assertThrows(Exception.class,() -> EvaluationSupportJudge.parse(json,value.toString(),"Paris","Alice is in Paris.",points));
    }
}

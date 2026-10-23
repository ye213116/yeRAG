package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.*;
import com.yizhaoqi.smartpai.rag.model.RagTypes.*;
import java.util.*;

/** Per-subscription protocol state. Transport EOF alone never means a completed answer. */
public final class GenerationDecoder {
    private final ObjectMapper json;
    private boolean done;
    private boolean modelOutput;
    private String reason;
    private Map<String,Long> usage;
    public GenerationDecoder(ObjectMapper json) { this.json=json; }
    public GenerationEvent accept(String payload) {
        if (payload==null || payload.isBlank()) return new GenerationEvent("",null,null,false);
        if ("[DONE]".equals(payload.trim())) { done=true; return new GenerationEvent("",reason,usage,true); }
        try {
            JsonNode root=json.readTree(payload);
            if (root.has("error")) throw new RagFailure(Status.FAILED,"MODEL_STREAM_ERROR");
            if (root.path("usage").isObject()) {
                Map<String,Long> values=new LinkedHashMap<>();
                root.get("usage").fields().forEachRemaining(e -> { if(e.getValue().isIntegralNumber()) values.put(e.getKey(),e.getValue().asLong()); });
                usage=Map.copyOf(values);
            }
            JsonNode choices=root.path("choices");
            if (!choices.isArray()) throw new IllegalArgumentException("Missing choices");
            if (choices.isEmpty()) {
                if (!root.has("usage")) throw new IllegalArgumentException("Empty model event");
                return new GenerationEvent("",reason,usage,done);
            }
            JsonNode choice=choices.get(0);
            String text=choice.path("delta").path("content").asText("");
            if(!text.isEmpty() || !choice.path("delta").path("reasoning_content").asText("").isEmpty()) modelOutput=true;
            if (done && !text.isEmpty()) throw new IllegalArgumentException("Answer after terminal event");
            if (choice.path("finish_reason").isTextual()) {
                String next=choice.path("finish_reason").asText();
                if (reason!=null && !reason.equals(next)) throw new IllegalArgumentException("Conflicting completion reason");
                reason=next; done=true;
            }
            return new GenerationEvent(text,reason,usage,done);
        } catch (RagFailure e) { throw e; }
        catch (Exception error) { throw new RagFailure(Status.FAILED,"INVALID_MODEL_STREAM"); }
    }
    public void verifyComplete() {
        if (!done) throw new RagFailure(Status.FAILED,"MODEL_STREAM_INCOMPLETE");
        if (reason!=null && Set.of("length","max_tokens","max_output_tokens").contains(reason.toLowerCase(Locale.ROOT)))
            throw new RagFailure(Status.TRUNCATED,"MODEL_OUTPUT_TRUNCATED");
        if (reason!=null && !Set.of("stop","end_turn","eos_token").contains(reason.toLowerCase(Locale.ROOT)))
            throw new RagFailure(Status.FAILED,"UNEXPECTED_MODEL_FINISH_REASON");
    }
    public boolean hasModelOutput() { return modelOutput; }
    public String reason() { return reason==null?(done?"PROTOCOL_DONE":"INCOMPLETE"):reason; }
    public Map<String,Long> usage() { return usage; }
}

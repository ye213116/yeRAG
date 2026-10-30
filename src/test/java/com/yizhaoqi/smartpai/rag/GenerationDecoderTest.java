package com.yizhaoqi.smartpai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.rag.model.RagTypes.Status;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationDecoderTest {
    @Test void stopAndUsageAreDistinctFromTransportEof() {
        var decoder=new GenerationDecoder(new ObjectMapper());
        assertEquals("答案",decoder.accept("{\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}").text());
        assertThrows(RagFailure.class,decoder::verifyComplete); assertNull(decoder.usage());
        decoder.accept("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        decoder.accept("{\"choices\":[],\"usage\":{\"total_tokens\":42}}");
        decoder.accept("[DONE]"); decoder.verifyComplete();
        assertEquals(42,decoder.usage().get("total_tokens"));
        assertThrows(RagFailure.class,() -> decoder.accept("{\"choices\":[{\"delta\":{\"content\":\"late\"}}]}"));
    }
    @Test void lengthIsTruncatedEvenIfDoneFollows() {
        var decoder=new GenerationDecoder(new ObjectMapper());
        decoder.accept("{\"choices\":[{\"finish_reason\":\"length\"}]}"); decoder.accept("[DONE]");
        assertEquals(Status.TRUNCATED,assertThrows(RagFailure.class,decoder::verifyComplete).status());
    }
    @Test void malformedAndConflictingTerminalEventsAreRejected() {
        var decoder=new GenerationDecoder(new ObjectMapper());
        assertThrows(RagFailure.class,() -> decoder.accept("not json"));
        decoder.accept("{\"choices\":[{\"finish_reason\":\"stop\"}]}");
        assertThrows(RagFailure.class,() -> decoder.accept("{\"choices\":[{\"finish_reason\":\"length\"}]}"));
    }
    @Test void modelTokenBudgetNeverSplitsCodePoints() {
        String value="a😀汉b";
        for(int budget=0;budget<=TokenBudget.count(value);budget++) {
            String prefix=TokenBudget.prefix(value,budget);
            assertTrue(value.startsWith(prefix));
            assertTrue(TokenBudget.count(prefix)<=budget);
            assertFalse(!prefix.isEmpty() && Character.isHighSurrogate(prefix.charAt(prefix.length()-1)));
        }
        assertEquals(value,TokenBudget.prefix(value,TokenBudget.count(value)));
        assertEquals(2,TokenBudget.count("Hello!"));
    }
}

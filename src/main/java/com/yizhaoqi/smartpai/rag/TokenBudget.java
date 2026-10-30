package com.yizhaoqi.smartpai.rag;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/** Offline model BPE counting. Provider usage remains authoritative for its private chat framing. */
public final class TokenBudget {
    public static final String DEFAULT_RESOURCE = "classpath:tokenizers/deepseek-v4/tokenizer.json.gz";
    public static final String KIND = "DEEPSEEK_V41_BPE_V1";
    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private TokenBudget() {}

    public static Counter counter(String resource) { return COUNTERS.computeIfAbsent(resource, Counter::new); }
    public static int count(String text) { return counter(DEFAULT_RESOURCE).count(text); }
    public static String prefix(String text, int budget) { return counter(DEFAULT_RESOURCE).prefix(text, budget); }

    public static String prefixChars(String text, int characters) {
        int end = Math.min(Math.max(0, characters), text.length());
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }

    public static final class Counter {
        private final HuggingFaceTokenizer tokenizer;
        private final String sha256;

        private Counter(String location) {
            if (!location.startsWith("classpath:") && !location.startsWith("file:"))
                throw new IllegalArgumentException("Tokenizer must be a bundled classpath or local file resource");
            try (var source = new DefaultResourceLoader().getResource(location).getInputStream();
                 var input = location.endsWith(".gz") ? new GZIPInputStream(source) : source) {
                byte[] data = input.readAllBytes();
                sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
                tokenizer = HuggingFaceTokenizer.newInstance(new ByteArrayInputStream(data), Map.of(
                        "addSpecialTokens", "false", "truncation", "false", "padding", "false",
                        "modelMaxLength", Integer.toString(Integer.MAX_VALUE)));
            } catch (Exception error) {
                throw new IllegalStateException("Cannot initialize the configured model tokenizer; byte estimation is not a fallback", error);
            }
        }

        public int count(String text) { return text == null || text.isEmpty() ? 0 : tokenizer.encode(text, false, false).getIds().length; }
        public String sha256() { return sha256; }
        public String kind() { return "HF_BPE_CHAT_WITH_PROTOCOL_RESERVE:" + sha256; }

        /** Count decoded model-visible content, not HTTP JSON escaping; reserve private API framing separately. */
        public int requestTokens(JsonNode request) {
            long result = 32;
            for (JsonNode message : request.path("messages")) {
                result += 32L + count(message.path("role").asText()) + count(message.path("content").asText(""));
                result += count(message.path("tool_call_id").asText(""));
                for (JsonNode call : message.path("tool_calls")) {
                    result += 16L + count(call.path("id").asText())
                            + count(call.path("function").path("name").asText())
                            + count(call.path("function").path("arguments").asText());
                }
            }
            for (JsonNode tool : request.path("tools")) result += 64L + count(tool.toString());
            return (int) Math.min(Integer.MAX_VALUE, result);
        }

        /** Returns a code-point-safe prefix that fits the token budget, without decoding partial UTF-8 tokens. */
        public String prefix(String text, int budget) {
            if (text == null || budget <= 0) return "";
            if (count(text) <= budget) return text;
            int low = 0, high = text.codePointCount(0, text.length());
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                String candidate = text.substring(0, text.offsetByCodePoints(0, middle));
                if (count(candidate) <= budget) low = middle;
                else high = middle - 1;
            }
            return text.substring(0, text.offsetByCodePoints(0, low));
        }
    }
}

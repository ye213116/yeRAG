package com.yizhaoqi.smartpai.rag;

import java.nio.charset.StandardCharsets;

/** Conservative UTF-8-byte estimate, explicitly NOT a model tokenizer. */
public final class TokenBudget {
    public static final String KIND = "ESTIMATED_UTF8_BYTES_V1";
    private TokenBudget() {}
    public static int count(String text) { return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length; }
    public static String prefixChars(String text,int characters) {
        int end=Math.min(Math.max(0,characters),text.length());
        if(end>0 && end<text.length() && Character.isHighSurrogate(text.charAt(end-1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0,end);
    }
    public static String prefix(String text, int budget) {
        int end = 0, used = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > budget) break;
            used += bytes; end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }
}

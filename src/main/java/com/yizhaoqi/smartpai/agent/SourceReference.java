package com.yizhaoqi.smartpai.agent;

import java.time.Instant;

/** Evidence metadata shared by local and web tools; never carries authorization. */
public record SourceReference(int number, String kind, String title, String fileMd5, Integer chunkId,
                              String url, String fetchedAt, String evidence) {
    public static SourceReference knowledge(int number, String title, String md5, Integer chunkId) {
        return new SourceReference(number, "knowledge", title, md5, chunkId, null, null, "document_chunk");
    }

    public static SourceReference web(int number, String title, String url, String evidence) {
        return new SourceReference(number, "web", title, null, null, url, Instant.now().toString(), evidence);
    }

    public String markdown() {
        String label = title == null ? "未命名来源" : title;
        label = label.replaceAll("[\\r\\n\\[\\]<>|()（）]", " ").replace("MD5:", "");
        label = label.substring(0, Math.min(label.length(), 200));
        if ("knowledge".equals(kind)) {
            return "- (来源#" + number + ": " + label + " | MD5:" + fileMd5 + ")"
                    + (chunkId == null ? "" : " · 片段 " + chunkId);
        }
        // Encode Markdown delimiters without changing the URL destination.
        String destination = url.replace("(", "%28").replace(")", "%29").replace("<", "%3C").replace(">", "%3E");
        return "- [来源#" + number + ": " + label + "](" + destination + ") · "
                + ("search_snippet".equals(evidence) ? "搜索摘要，未读取正文" : "网页正文")
                + " · 获取于 " + fetchedAt;
    }
}

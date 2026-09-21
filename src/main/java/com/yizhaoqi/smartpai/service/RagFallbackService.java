package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import reactor.core.Disposable;

@Service
public class RagFallbackService {
    private final HybridSearchService searchService;
    private final DeepSeekClient deepSeekClient;

    public RagFallbackService(HybridSearchService searchService, DeepSeekClient deepSeekClient) {
        this.searchService = searchService;
        this.deepSeekClient = deepSeekClient;
    }

    public Disposable stream(String userId, String userMessage, List<Map<String, String>> history,
                       Consumer<String> onChunk, Consumer<Map<Integer, String>> onReferences,
                       Consumer<Throwable> onError, Runnable onComplete) {
        List<SearchResult> results = searchService.searchWithPermission(userMessage, userId, 5);
        Map<Integer, String> references = new LinkedHashMap<>();
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            int ref = i + 1;
            String content = result.getTextContent() == null ? "" : result.getTextContent();
            if (content.length() > 300) content = content.substring(0, 300) + "…";
            context.append('[').append(ref).append("] (")
                    .append(result.getFileName() == null ? "unknown" : result.getFileName())
                    .append(" | MD5:").append(result.getFileMd5()).append(") ").append(content).append('\n');
            if (result.getFileMd5() != null) references.put(ref, result.getFileMd5());
        }
        onReferences.accept(Map.copyOf(references));
        return deepSeekClient.streamResponseDisposable(userMessage, context.toString(), history, onChunk, onError, onComplete);
    }
}

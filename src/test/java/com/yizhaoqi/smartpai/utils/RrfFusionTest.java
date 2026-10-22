package com.yizhaoqi.smartpai.utils;

import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    private static SearchResult hit(String md5, int chunkId) {
        return new SearchResult(md5, chunkId, "text-" + md5 + '-' + chunkId, 0.5, "user", "TECH", false);
    }

    private static List<String> keys(List<SearchResult> results) {
        return results.stream().map(result -> result.getFileMd5() + '#' + result.getChunkId()).toList();
    }

    @Test
    void fusesByRankAndRewardsDocumentsHitByBothRoutes() {
        List<SearchResult> bm25 = List.of(hit("a", 1), hit("b", 1));
        List<SearchResult> knn = List.of(hit("b", 1), hit("c", 1));

        List<SearchResult> fused = RrfFusion.fuse(bm25, knn, 60d, 1d, 1d);

        // b 两路都命中：1/61 + 1/61；a 只有 BM25 第 1 名：1/61；c 只有 KNN 第 2 名：1/62
        assertEquals(List.of("b#1", "a#1", "c#1"), keys(fused));
        // b 在 BM25 第 2 名、KNN 第 1 名：1/62 + 1/61
        assertEquals(1d / 62d + 1d / 61d, fused.get(0).getScore(), 1e-12);
        assertEquals(1d / 61d, fused.get(1).getScore(), 1e-12);
        assertEquals(1d / 62d, fused.get(2).getScore(), 1e-12);
    }

    @Test
    void deduplicatesTheSameChunkAcrossRoutes() {
        List<SearchResult> bm25 = List.of(hit("a", 1), hit("a", 2));
        List<SearchResult> knn = List.of(hit("a", 1));

        List<SearchResult> fused = RrfFusion.fuse(bm25, knn, 60d, 1d, 1d);

        assertEquals(2, fused.size());
        // a#1 两路都是第 1 名：1/61 + 1/61
        assertEquals(2d / 61d, fused.get(0).getScore(), 1e-12);
    }

    @Test
    void keepsSingleRouteOrderWhenTheOtherRouteIsEmpty() {
        List<SearchResult> bm25 = List.of(hit("a", 1), hit("b", 1), hit("c", 1));

        List<SearchResult> fused = RrfFusion.fuse(bm25, List.of(), 60d, 1d, 1d);

        assertEquals(List.of("a#1", "b#1", "c#1"), keys(fused));
    }

    @Test
    void usesConfiguredWeights() {
        List<SearchResult> bm25 = List.of(hit("a", 1));
        List<SearchResult> knn = List.of(hit("b", 1));

        List<SearchResult> fused = RrfFusion.fuse(bm25, knn, 60d, 1d, 3d);

        assertEquals(List.of("b#1", "a#1"), keys(fused));
        assertEquals(3d / 61d, fused.get(0).getScore(), 1e-12);
    }

    @Test
    void fallsBackToDefaultRankConstantAndHandlesEmptyInput() {
        List<SearchResult> fused = RrfFusion.fuse(List.of(hit("a", 1)), List.of(), 0d, 1d, 1d);

        assertEquals(1d / 61d, fused.get(0).getScore(), 1e-12);
        assertTrue(RrfFusion.fuse(null, null, 60d, 1d, 1d).isEmpty());
    }
}

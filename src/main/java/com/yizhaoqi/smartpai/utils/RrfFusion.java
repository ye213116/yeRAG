package com.yizhaoqi.smartpai.utils;

import com.yizhaoqi.smartpai.entity.SearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：只用两路召回的排名，不比较原始分数，因此天然规避
 * BM25 分数与向量相似度量纲不同、无法直接加权的问题。
 *
 * <pre>
 * score(d) = bm25Weight / (k + rank_bm25(d)) + knnWeight / (k + rank_knn(d))
 * </pre>
 *
 * <p>rank 从 1 开始；同一 chunk（fileMd5 + chunkId）在两路都命中时分数累加，
 * 这是 RRF 奖励“多路共识”的方式。</p>
 */
public final class RrfFusion {

    private static final double DEFAULT_RANK_CONSTANT = 60d;

    private RrfFusion() {
    }

    /**
     * 融合两路召回结果，按 RRF 分数降序返回。
     *
     * @param bm25Hits     BM25 路结果，按相关性降序
     * @param knnHits      KNN 路结果，按相似度降序
     * @param rankConstant RRF 平滑常数 k，非正数时退回默认 60
     * @param bm25Weight   BM25 路权重
     * @param knnWeight    KNN 路权重
     * @return 去重后的融合结果；两路都为空时返回空列表
     */
    public static List<SearchResult> fuse(List<SearchResult> bm25Hits,
                                          List<SearchResult> knnHits,
                                          double rankConstant,
                                          double bm25Weight,
                                          double knnWeight) {
        double constant = rankConstant > 0 ? rankConstant : DEFAULT_RANK_CONSTANT;
        Map<String, Accumulator> fused = new LinkedHashMap<>();
        accumulate(fused, bm25Hits, bm25Weight, constant);
        accumulate(fused, knnHits, knnWeight, constant);

        List<SearchResult> results = new ArrayList<>(fused.size());
        for (Accumulator accumulator : fused.values()) {
            SearchResult source = accumulator.source;
            results.add(new SearchResult(
                    source.getFileMd5(),
                    source.getChunkId(),
                    source.getTextContent(),
                    accumulator.score,
                    source.getUserId(),
                    source.getOrgTag(),
                    Boolean.TRUE.equals(source.getIsPublic())));
        }
        // List.sort 是稳定排序：并列时保留先出现的（BM25 路优先），结果可复现
        results.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));
        return results;
    }

    private static void accumulate(Map<String, Accumulator> fused, List<SearchResult> hits,
                                   double weight, double rankConstant) {
        if (hits == null || hits.isEmpty() || weight <= 0) {
            return;
        }
        int rank = 0;
        for (SearchResult hit : hits) {
            if (hit == null) {
                continue;
            }
            rank++;
            Accumulator accumulator = fused.computeIfAbsent(keyOf(hit), ignored -> new Accumulator(hit));
            accumulator.score += weight / (rankConstant + rank);
        }
    }

    static String keyOf(SearchResult result) {
        String fileMd5 = result.getFileMd5() == null ? "" : result.getFileMd5();
        String chunkId = result.getChunkId() == null ? "" : result.getChunkId().toString();
        return fileMd5 + '#' + chunkId;
    }

    private static final class Accumulator {
        private final SearchResult source;
        private double score;

        private Accumulator(SearchResult source) {
            this.source = source;
        }
    }
}

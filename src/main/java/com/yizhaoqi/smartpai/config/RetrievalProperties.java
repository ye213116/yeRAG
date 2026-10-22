package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 检索链路配置：双路召回窗口、RRF 融合参数与重排序参数。
 *
 * <p>召回模式默认 {@link Mode#RRF}；设为 {@link Mode#LEGACY} 可回滚到旧的
 * “单请求 KNN + BM25 rescore” 实现，便于灰度对比与线上回滚。</p>
 *
 * <p>重排序未配置 url 或 api-key 时不会启动失败，而是自动降级为纯 RRF 结果。</p>
 */
@Component
@ConfigurationProperties(prefix = "ai.retrieval")
@Data
public class RetrievalProperties {

    public enum Mode {
        /** BM25 + KNN 双路召回，RRF 融合后再重排序 */
        RRF,
        /** 旧的单请求 KNN + BM25 rescore 实现 */
        LEGACY
    }

    /** 召回模式 */
    private Mode mode = Mode.RRF;

    /** 每路召回窗口 = topK * recallMultiplier */
    private int recallMultiplier = 30;

    /** 对外允许的 topK 上限，避免 (topK * 30) 被放大成 ES 负载事故 */
    private int maxTopK = 50;

    /** RRF 平滑常数，论文与 Elasticsearch 默认值均为 60 */
    private double rrfRankConstant = 60d;

    /** RRF 中各路的权重，默认等权 */
    private double bm25Weight = 1d;
    private double knnWeight = 1d;

    /** 单路召回超时；两路并行，各自独立计时，超时的一路按空结果处理 */
    private Duration routeTimeout = Duration.ofSeconds(2);

    private Rerank rerank = new Rerank();

    @Data
    public static class Rerank {
        /** 是否启用重排序 */
        private boolean enabled = true;

        /** gte-rerank 服务地址（例如 DashScope 文本重排端点），留空则只用 RRF 排序 */
        private String url = "";

        /** 重排序 API Key，留空时复用 embedding.api.key */
        private String apiKey = "";

        /** 重排序模型名 */
        private String model = "gte-rerank";

        /** 进入重排序的候选数上限（取自 RRF 结果头部） */
        private int topN = 50;

        /** 单条候选送重排前的截断长度，控制请求体积 */
        private int maxDocChars = 1000;

        /** 重排序超时；超时或失败自动退回 RRF 结果 */
        private Duration timeout = Duration.ofMillis(1500);
    }
}

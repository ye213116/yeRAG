package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.client.RerankClient;
import com.yizhaoqi.smartpai.config.RetrievalProperties;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.utils.RrfFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 混合检索服务。
 *
 * <p>默认链路：BM25 与 KNN 双路并行召回 → RRF 按排名融合 → gte-rerank 重排序。
 * RRF 只比较排名、不比较分数，因此不需要为“BM25 分”和“向量分”找换算系数；
 * 重排序超时或失败时自动退回 RRF 结果。</p>
 *
 * <p>三种情况都会降级但不会中断检索：向量生成失败只走 BM25 路；两路召回中一路失败
 * 只用另一路；重排序失败只用 RRF 排序。所有结果都已按权限过滤：本人文档、公开文档、
 * 所属组织（含层级）文档。</p>
 *
 * <p>{@code ai.retrieval.mode=legacy} 可切回旧的“单请求 KNN + BM25 rescore”实现用于回滚。</p>
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    private static final String INDEX = HybridRecallClient.INDEX;
    private static final String TEXT_FIELD = "textContent";
    private static final String VECTOR_FIELD = "vector";

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final HybridRecallClient recallClient;
    private final RerankClient rerankClient;
    private final UserRepository userRepository;
    private final OrgTagCacheService orgTagCacheService;
    private final FileUploadRepository fileUploadRepository;
    private final RetrievalProperties retrieval;

    public HybridSearchService(ElasticsearchClient esClient,
                               EmbeddingClient embeddingClient,
                               HybridRecallClient recallClient,
                               RerankClient rerankClient,
                               UserRepository userRepository,
                               OrgTagCacheService orgTagCacheService,
                               FileUploadRepository fileUploadRepository,
                               RetrievalProperties retrieval) {
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
        this.recallClient = recallClient;
        this.rerankClient = rerankClient;
        this.userRepository = userRepository;
        this.orgTagCacheService = orgTagCacheService;
        this.fileUploadRepository = fileUploadRepository;
        this.retrieval = retrieval;
    }

    /**
     * 带权限的混合检索。
     *
     * @param query  查询字符串
     * @param userId 已认证用户（由服务端传入，绝不接受模型参数）
     * @param topK   期望返回条数，会被收敛到 {@code ai.retrieval.max-top-k}
     * @return 已按权限过滤的检索结果
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        int clampedTopK = clampTopK(topK);
        logger.debug("开始带权限检索，查询: {}, 用户ID: {}, topK: {}(入参 {})", query, userId, clampedTopK, topK);

        final List<String> userEffectiveTags;
        final String userDbId;
        try {
            userEffectiveTags = getUserEffectiveOrgTags(userId);
            userDbId = getUserDbId(userId);
        } catch (RuntimeException e) {
            logger.error("解析用户权限失败，检索终止", e);
            return new ArrayList<>();
        }

        if (retrieval.getMode() == RetrievalProperties.Mode.LEGACY) {
            return legacySearchWithPermission(query, userDbId, userEffectiveTags, clampedTopK);
        }
        try {
            return rrfSearchWithPermission(query, userDbId, userEffectiveTags, clampedTopK);
        } catch (RuntimeException e) {
            logger.error("双路召回检索失败", e);
            return new ArrayList<>();
        }
    }

    /** Pipeline recall boundary. Reranking belongs to RerankStage; all failures remain typed. */
    public reactor.core.publisher.Mono<com.yizhaoqi.smartpai.rag.model.RagTypes.RetrievalResult> searchWithPermissionDetailed(
            String query,String identity,int candidateK,double rankConstant) {
        return reactor.core.publisher.Mono.defer(() -> {
            User user=resolveUser(identity);
            List<String> tags=orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            if(tags==null) return reactor.core.publisher.Mono.error(new SecurityException("Permission scope unavailable"));
            tags=List.copyOf(tags);
            String owner=user.getId().toString(); Query acl=HybridRecallClient.aclQuery(owner,tags);
            final List<String> effectiveTags=tags;
            java.util.function.Predicate<SearchResult> allowed=hit -> owner.equals(hit.getUserId())
                || Boolean.TRUE.equals(hit.getIsPublic()) || effectiveTags.contains(hit.getOrgTag());
            int window=Math.min(10000,Math.max(1,candidateK));
            if(retrieval.getMode()==RetrievalProperties.Mode.LEGACY) {
                return reactor.core.publisher.Mono.fromCallable(() -> {
                    var values=embedToVectorList(query); return values==null?List.<Float>of():values;
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).timeout(retrieval.getRouteTimeout())
                    .onErrorReturn(List.of()).flatMap(values -> recallClient.recallDetailed(values.isEmpty()?"bm25":"legacy",query,values,acl,window,retrieval.getRouteTimeout(),allowed)
                        .map(route -> new com.yizhaoqi.smartpai.rag.model.RagTypes.RetrievalResult(
                            route.status()==com.yizhaoqi.smartpai.rag.model.RagTypes.Status.FAILED?route.status():
                            values.isEmpty()?com.yizhaoqi.smartpai.rag.model.RagTypes.Status.DEGRADED:
                            route.candidates().isEmpty()?com.yizhaoqi.smartpai.rag.model.RagTypes.Status.NO_EVIDENCE:com.yizhaoqi.smartpai.rag.model.RagTypes.Status.SUCCEEDED,
                            List.of(route),route.candidates()))).map(this::attachDetailedNames);
            }
            long vectorStarted=System.nanoTime();
            var text=recallClient.recallDetailed("bm25",query,null,acl,window,retrieval.getRouteTimeout(),allowed);
            var vector=reactor.core.publisher.Mono.fromCallable(() -> {
                List<Float> values=embedToVectorList(query);
                if(values==null || values.isEmpty()) throw new com.yizhaoqi.smartpai.rag.RagFailure(com.yizhaoqi.smartpai.rag.model.RagTypes.Status.FAILED,"EMBEDDING_UNAVAILABLE");
                return values;
            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .timeout(retrieval.getRouteTimeout())
                .flatMap(values -> recallClient.recallDetailed("vector",query,values,acl,window,retrieval.getRouteTimeout(),allowed))
                .map(route -> new com.yizhaoqi.smartpai.rag.model.RagTypes.RouteResult(route.route(),route.status(),route.candidates(),(System.nanoTime()-vectorStarted)/1_000_000,route.reason()))
                .onErrorResume(error -> {
                    if(error instanceof SecurityException) return reactor.core.publisher.Mono.error(error);
                    return reactor.core.publisher.Mono.just(new com.yizhaoqi.smartpai.rag.model.RagTypes.RouteResult("vector",
                        com.yizhaoqi.smartpai.rag.model.RagTypes.Status.FAILED,List.of(),(System.nanoTime()-vectorStarted)/1_000_000,error instanceof com.yizhaoqi.smartpai.rag.RagFailure?error.getMessage():error.getClass().getSimpleName()));
                });
            return reactor.core.publisher.Mono.zip(text,vector).map(pair ->
                com.yizhaoqi.smartpai.rag.RankFusion.fuse(List.of(pair.getT1(),pair.getT2()),rankConstant,
                    Map.of("bm25",retrieval.getBm25Weight(),"vector",retrieval.getKnnWeight())))
                .map(this::attachDetailedNames);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private com.yizhaoqi.smartpai.rag.model.RagTypes.RetrievalResult attachDetailedNames(com.yizhaoqi.smartpai.rag.model.RagTypes.RetrievalResult result) {
        List<SearchResult> labels=result.candidates().stream().map(c -> new SearchResult(c.fileMd5(),c.chunkIndex(),c.text(),c.score(),c.owner(),null,false)).toList();
        attachFileNames(labels);
        Map<OwnerDocument,String> names=new java.util.HashMap<>();
        for(SearchResult label:labels) if(label.getFileName()!=null) names.put(new OwnerDocument(label.getUserId(),label.getFileMd5()),label.getFileName());
        java.util.function.Function<com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate,com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate> enrich=c ->
            new com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate(c.id(),c.fileMd5(),c.chunkIndex(),c.owner(),names.get(new OwnerDocument(c.owner(),c.fileMd5())),c.text(),c.score(),c.routeScores(),c.sources(),c.esIndex(),c.esHitId());
        return new com.yizhaoqi.smartpai.rag.model.RagTypes.RetrievalResult(result.status(),result.routes().stream().map(route ->
            new com.yizhaoqi.smartpai.rag.model.RagTypes.RouteResult(route.route(),route.status(),route.candidates().stream().map(enrich).toList(),route.elapsedMs(),route.reason())).toList(),
            result.candidates().stream().map(enrich).toList());
    }

    public record CorpusScan(java.util.List<com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate> chunks,String permissionFingerprint) {}

    /** Trusted offline preflight only: scan selected files, with the same fail-closed route ACL. */
    public CorpusScan scanCorpusWithPermission(String identity,java.util.List<String> md5s) {
        if(md5s.isEmpty() || md5s.size()>100 || md5s.stream().anyMatch(v -> !v.matches("[a-fA-F0-9]{32}")))
            throw new IllegalArgumentException("Bounded corpus MD5 list required");
        User user=resolveUser(identity);
        java.util.List<String> tags=orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        if(tags==null) throw new SecurityException("Permission scope unavailable");
        tags=java.util.List.copyOf(tags);
        String owner=user.getId().toString(); Query acl=HybridRecallClient.aclQuery(owner,tags);
        java.util.List<com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate> chunks=new java.util.ArrayList<>();
        String scroll=null;
        try {
            var values=md5s.stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList();
            SearchResponse<EsDocument> response=esClient.search(s -> s.index(INDEX).size(500).scroll(t -> t.time("1m"))
                .source(src -> src.filter(f -> f.excludes(VECTOR_FIELD)))
                .query(q -> q.bool(b -> b.filter(acl).filter(f -> f.terms(t -> t.field("fileMd5").terms(v -> v.value(values)))))),EsDocument.class);
            while(true) {
                scroll=response.scrollId();
                if(response.timedOut() || response.shards().failed().longValue()>0) throw new IllegalStateException("Incomplete corpus search response");
                for(var hit:response.hits().hits()) {
                    EsDocument d=hit.source();
                    if(d==null || d.getChunkId()==null || d.getTextContent()==null) throw new IllegalStateException("Incomplete corpus chunk");
                    if(!md5s.contains(d.getFileMd5()) || !(owner.equals(d.getUserId()) || d.isPublic() || tags.contains(d.getOrgTag())))
                        throw new SecurityException("Unauthorized corpus scan result");
                    chunks.add(new com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate(hit.index()+":"+hit.id(),d.getFileMd5(),d.getChunkId(),d.getUserId(),null,d.getTextContent(),0,java.util.Map.of(),java.util.List.of(),hit.index(),hit.id()));
                    if(chunks.size()>10000) throw new IllegalStateException("Corpus scan exceeds 10000 chunks");
                }
                if(response.hits().hits().isEmpty()) break;
                if(scroll==null) throw new IllegalStateException("Missing corpus scroll cursor");
                final String cursor=scroll;
                var next=esClient.scroll(s -> s.scrollId(cursor).scroll(t -> t.time("1m")),EsDocument.class);
                response=new SearchResponse.Builder<EsDocument>().took(next.took()).timedOut(next.timedOut()).shards(next.shards()).hits(next.hits()).scrollId(next.scrollId()).build();
            }
        } catch(java.io.IOException unavailable) { throw new IllegalStateException("Corpus scan unavailable",unavailable); }
        finally {
            if(scroll!=null) try { final String cursor=scroll; esClient.clearScroll(c -> c.scrollId(cursor)); }
            catch(Exception ignored) { logger.warn("Could not close evaluation scroll"); }
        }
        return new CorpusScan(java.util.List.copyOf(chunks),com.yizhaoqi.smartpai.rag.trace.EvidenceRecorder.hash(owner+":"+String.join(",",tags.stream().sorted().toList())));
    }

    /** Read a bounded chunk range with the same pre-filter ACL as hybrid retrieval. */
    public List<SearchResult> readDocumentWithPermission(String fileMd5, int startChunk, int chunkCount, String userId) {
        if (fileMd5 == null || !fileMd5.matches("[a-fA-F0-9]{32}") || startChunk < 0
                || chunkCount < 1 || chunkCount > 5 || startChunk > Integer.MAX_VALUE - chunkCount) {
            throw new IllegalArgumentException("invalid document range");
        }
        String userDbId = getUserDbId(userId);
        Query acl = HybridRecallClient.aclQuery(userDbId, getUserEffectiveOrgTags(userId));
        try {
            SearchResponse<EsDocument> response = esClient.search(search -> search.index(INDEX)
                    .query(q -> q.bool(b -> b.filter(acl)
                            .filter(f -> f.term(t -> t.field("fileMd5").value(fileMd5)))
                            .filter(f -> f.range(r -> r.field("chunkId").gte(co.elastic.clients.json.JsonData.of(startChunk))
                                    .lt(co.elastic.clients.json.JsonData.of(startChunk + chunkCount))))))
                    .sort(s -> s.field(f -> f.field("chunkId").order(co.elastic.clients.elasticsearch._types.SortOrder.Asc)))
                    .source(s -> s.filter(f -> f.excludes(VECTOR_FIELD))).size(chunkCount), EsDocument.class);
            List<SearchResult> results = new ArrayList<>();
            response.hits().hits().forEach(hit -> {
                EsDocument d = hit.source();
                if (d != null) results.add(new SearchResult(d.getFileMd5(), d.getChunkId(), d.getTextContent(),
                        0d, d.getUserId(), d.getOrgTag(), d.isPublic()));
            });
            attachFileNames(results);
            return results;
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("document reading unavailable", unavailable);
        }
    }

    /** 对外 topK 上限校验：窗口是 topK 的 30 倍，必须挡住超大入参 */
    private int clampTopK(int topK) {
        int max = Math.max(1, retrieval.getMaxTopK());
        if (topK > max) {
            logger.warn("topK {} 超过配置上限 {}，已收敛", topK, max);
        }
        return Math.max(1, Math.min(max, topK));
    }

    private List<SearchResult> rrfSearchWithPermission(String query, String userDbId,
                                                       List<String> userEffectiveTags, int topK) {
        Query acl = HybridRecallClient.aclQuery(userDbId, userEffectiveTags);
        int window = topK * Math.max(1, retrieval.getRecallMultiplier());

        final List<Float> queryVector = embedToVectorList(query);
        HybridRecallClient.RecallResult recall;
        if (queryVector == null) {
            logger.warn("向量生成失败，本次只走 BM25 路（仍会做重排序）");
            recall = new HybridRecallClient.RecallResult(
                    recallClient.recallBm25(query, acl, window, retrieval.getRouteTimeout()), List.of());
        } else {
            recall = recallClient.recallBoth(query, queryVector, acl, window, retrieval.getRouteTimeout());
        }
        logger.debug("双路召回完成 - BM25: {}, KNN: {}, 窗口: {}",
                recall.bm25().size(), recall.knn().size(), window);

        List<SearchResult> fused = RrfFusion.fuse(recall.bm25(), recall.knn(),
                retrieval.getRrfRankConstant(), retrieval.getBm25Weight(), retrieval.getKnnWeight());
        if (fused.isEmpty()) {
            logger.debug("双路召回均无结果");
            return new ArrayList<>();
        }
        logger.debug("RRF 融合后候选数: {}", fused.size());

        List<SearchResult> ranked = rerankOrFuse(query, fused, topK);
        attachFileNames(ranked);
        return ranked;
    }

    /**
     * 取 RRF 结果头部的候选做重排序；重排序不可用、超时或失败时退回 RRF 排序。
     *
     * <p>重排成功时返回的是重排分数，失败时返回的是 RRF 分数 —— 两者量纲不同，
     * 因此失败路径不会把重排结果和 RRF 尾部混排在一起。</p>
     */
    private List<SearchResult> rerankOrFuse(String query, List<SearchResult> fused, int topK) {
        List<SearchResult> fallback = new ArrayList<>(fused.subList(0, Math.min(topK, fused.size())));
        RetrievalProperties.Rerank config = retrieval.getRerank();
        if (!config.isEnabled() || !rerankClient.available()) {
            return fallback;
        }
        int limit = Math.min(fused.size(), Math.max(1, config.getTopN()));
        if (limit <= 1) {
            return fallback;
        }
        List<SearchResult> candidates = fused.subList(0, limit);
        List<String> documents = candidates.stream()
                .map(candidate -> truncate(candidate.getTextContent(), config.getMaxDocChars()))
                .toList();
        List<RerankClient.RerankHit> hits = rerankClient.rerank(query, documents, Math.min(topK, limit));
        if (hits == null || hits.isEmpty()) {
            logger.warn("重排序超时或失败，退回 RRF 结果 - 候选数: {}, 返回数: {}", limit, fallback.size());
            return fallback;
        }
        List<SearchResult> ranked = new ArrayList<>(hits.size());
        for (RerankClient.RerankHit hit : hits) {
            SearchResult candidate = candidates.get(hit.index());
            ranked.add(new SearchResult(
                    candidate.getFileMd5(),
                    candidate.getChunkId(),
                    candidate.getTextContent(),
                    hit.score(),
                    candidate.getUserId(),
                    candidate.getOrgTag(),
                    Boolean.TRUE.equals(candidate.getIsPublic())));
        }
        logger.debug("重排序完成 - 候选: {}, 返回: {}", limit, Math.min(topK, ranked.size()));
        return ranked.size() > topK ? new ArrayList<>(ranked.subList(0, topK)) : ranked;
    }

    /**
     * 旧实现（{@code ai.retrieval.mode=legacy}）：单个 ES 请求内 KNN 召回 + BM25 rescore。
     * 关键词分和向量分靠固定权重相加，保留它只是为了灰度对比与线上回滚。
     */
    private List<SearchResult> legacySearchWithPermission(String query, String userDbId,
                                                          List<String> userEffectiveTags, int topK) {
        try {
            final List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            Query acl = HybridRecallClient.aclQuery(userDbId, userEffectiveTags);
            final int recallK = topK * Math.max(1, retrieval.getRecallMultiplier());
            SearchResponse<EsDocument> response = esClient.search(search -> {
                search.index(INDEX);
                search.knn(knn -> knn
                        .field(VECTOR_FIELD)
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK).filter(acl));
                search.query(queryBuilder -> queryBuilder.bool(bool -> bool
                        .must(must -> must.match(match -> match.field(TEXT_FIELD).query(query)))
                        .filter(acl)));
                search.rescore(rescore -> rescore
                        .windowSize(recallK)
                        .query(rescoreQuery -> rescoreQuery
                                .queryWeight(0.2d)
                                .rescoreQueryWeight(1.0d)
                                .query(inner -> inner.match(match -> match
                                        .field(TEXT_FIELD)
                                        .query(query)
                                        .operator(Operator.And)))));
                search.size(topK);
                return search;
            }, EsDocument.class);

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        EsDocument source = hit.source();
                        assert source != null;
                        return new SearchResult(
                                source.getFileMd5(),
                                source.getChunkId(),
                                source.getTextContent(),
                                hit.score(),
                                source.getUserId(),
                                source.getOrgTag(),
                                source.isPublic());
                    })
                    .toList();

            logger.debug("legacy 检索返回结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("legacy 检索失败，尝试纯文本后备", e);
            try {
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 仅使用文本匹配的带权限搜索，用于向量生成失败或 legacy 链路的后备方案。
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
                                                           List<String> userEffectiveTags, int topK) {
        try {
            Query acl = HybridRecallClient.aclQuery(userDbId, userEffectiveTags);
            SearchResponse<EsDocument> response = esClient.search(search -> search
                    .index(INDEX)
                    .query(queryBuilder -> queryBuilder.bool(bool -> bool
                            .must(must -> must.match(match -> match.field(TEXT_FIELD).query(query)))
                            .filter(acl)))
                    .minScore(0.3d)
                    .size(topK), EsDocument.class);

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        EsDocument source = hit.source();
                        assert source != null;
                        return new SearchResult(
                                source.getFileMd5(),
                                source.getChunkId(),
                                source.getTextContent(),
                                hit.score(),
                                source.getUserId(),
                                source.getOrgTag(),
                                source.isPublic());
                    })
                    .toList();

            logger.debug("纯文本搜索返回结果数量: {}", results.size());
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 原始搜索方法，不包含权限过滤，保留向后兼容性。
     *
     * <p>新代码必须使用 {@link #searchWithPermission(String, String, int)}。</p>
     */
    public List<SearchResult> search(String query, int topK) {
        int clampedTopK = clampTopK(topK);
        try {
            logger.warn("使用了没有权限过滤的搜索方法，建议使用 searchWithPermission 方法");
            final List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearch(query, clampedTopK);
            }

            final int recallK = clampedTopK * Math.max(1, retrieval.getRecallMultiplier());
            SearchResponse<EsDocument> response = esClient.search(search -> {
                search.index(INDEX);
                search.knn(knn -> knn
                        .field(VECTOR_FIELD)
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK));
                search.query(queryBuilder -> queryBuilder.match(match -> match.field(TEXT_FIELD).query(query)));
                search.rescore(rescore -> rescore
                        .windowSize(recallK)
                        .query(rescoreQuery -> rescoreQuery
                                .queryWeight(0.2d)
                                .rescoreQueryWeight(1.0d)
                                .query(inner -> inner.match(match -> match
                                        .field(TEXT_FIELD)
                                        .query(query)
                                        .operator(Operator.And)))));
                search.size(clampedTopK);
                return search;
            }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        EsDocument source = hit.source();
                        assert source != null;
                        return new SearchResult(
                                source.getFileMd5(),
                                source.getChunkId(),
                                source.getTextContent(),
                                hit.score());
                    })
                    .toList();
        } catch (Exception e) {
            logger.error("搜索失败", e);
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textOnlySearch(query, clampedTopK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                throw new RuntimeException("搜索完全失败", fallbackError);
            }
        }
    }

    /** 仅使用文本匹配的搜索方法（无权限过滤，兼容旧接口） */
    private List<SearchResult> textOnlySearch(String query, int topK) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(search -> search
                .index(INDEX)
                .query(queryBuilder -> queryBuilder.match(match -> match.field(TEXT_FIELD).query(query)))
                .size(topK), EsDocument.class);

        return response.hits().hits().stream()
                .map(hit -> {
                    EsDocument source = hit.source();
                    assert source != null;
                    return new SearchResult(
                            source.getFileMd5(),
                            source.getChunkId(),
                            source.getTextContent(),
                            hit.score());
                })
                .toList();
    }

    /** 生成查询向量，返回 List<Float>，失败时返回 null */
    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vectors = embeddingClient.embed(List.of(text));
            if (vectors == null || vectors.isEmpty()) {
                logger.warn("生成的向量为空");
                return null;
            }
            float[] raw = vectors.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float value : raw) {
                list.add(value);
            }
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int limit = maxChars > 0 ? maxChars : 1000;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /** 获取用户的有效组织标签（包含层级关系） */
    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = resolveUser(userId);
            List<String> effectiveTags = orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
            logger.debug("用户 {} 的有效组织标签: {}", user.getUsername(), effectiveTags);
            return effectiveTags == null ? Collections.emptyList() : effectiveTags;
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** 获取用户的数据库ID用于权限过滤 */
    private String getUserDbId(String userId) {
        try {
            return resolveUser(userId).getId().toString();
        } catch (NumberFormatException e) {
            // 非数字时按用户名查找，取数据库ID
            User user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            return user.getId().toString();
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            logger.error("获取用户数据库ID失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    private User resolveUser(String userId) {
        if(userId==null || userId.isBlank()) throw new SecurityException("Authenticated user required");
        if(userId.startsWith("name:")) return userRepository.findByUsername(userId.substring(5)).orElseThrow(() -> new SecurityException("Unknown user"));
        if(userId.startsWith("id:")) {
            try { return userRepository.findById(Long.parseLong(userId.substring(3))).orElseThrow(() -> new SecurityException("Unknown user")); }
            catch(NumberFormatException invalid) { throw new SecurityException("Invalid user ID"); }
        }
        try {
            Long userIdLong = Long.parseLong(userId);
            return userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found with ID: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private record OwnerDocument(String owner,String md5) {}

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .filter(md5 -> md5 != null)
                    .collect(Collectors.toSet());
            if (md5Set.isEmpty()) {
                return;
            }
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<OwnerDocument,String> names=new java.util.HashMap<>();
            for(FileUpload upload:uploads) if(upload.getFileName()!=null)
                names.putIfAbsent(new OwnerDocument(upload.getUserId(),upload.getFileMd5()),upload.getFileName());
            results.forEach(result -> result.setFileName(names.get(new OwnerDocument(result.getUserId(),result.getFileMd5()))));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}

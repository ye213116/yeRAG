package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 双路召回：BM25 关键词路与 KNN 向量路彼此独立，各自在自己的召回窗口内做前置权限过滤，
 * 两路并行执行。
 *
 * <p>权限过滤必须前置（{@code knn.filter} / {@code bool.filter}）：如果放在后过滤位置，
 * 无权限文档会先吃掉召回窗口、把有效候选挤掉。</p>
 *
 * <p>任何一路失败或超时都只把该路结果按空处理，另一路仍然返回，
 * 避免单路故障把整次检索打成空结果。</p>
 */
@Service
public class HybridRecallClient {

    /** 知识库索引名 */
    public static final String INDEX = "knowledge_base";

    private static final Logger logger = LoggerFactory.getLogger(HybridRecallClient.class);
    private static final String VECTOR_FIELD = "vector";
    private static final String TEXT_FIELD = "textContent";
    private static final int RECALL_THREADS = 4;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private final ElasticsearchClient esClient;
    private final ExecutorService executor;

    public HybridRecallClient(ElasticsearchClient esClient) {
        this.esClient = esClient;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "hybrid-recall-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(RECALL_THREADS, threadFactory);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    protected String indexName() { return INDEX; }

    /** 两路召回结果，按各自的排名顺序排列 */
    public record RecallResult(List<SearchResult> bm25, List<SearchResult> knn) {
        public boolean isEmpty() {
            return bm25.isEmpty() && knn.isEmpty();
        }
    }

    /**
     * 权限过滤条件：本人文档、公开文档、所属组织（含层级）文档三者取或。
     * 两路召回必须使用完全相同的过滤条件。
     */
    public static Query aclQuery(String userDbId, List<String> orgTags) {
        List<String> tags = orgTags == null ? List.of() : orgTags;
        return Query.of(query -> query.bool(bool -> {
            bool.minimumShouldMatch("1");
            if (userDbId != null && !userDbId.isBlank()) {
                bool.should(should -> should.term(term -> term.field("userId").value(userDbId)));
            }
            // 公开标记同时兼容 public / isPublic 两种写入命名，避免字段名不一致导致 ACL 漏判
            bool.should(should -> should.bool(publicFlag -> publicFlag
                    .minimumShouldMatch("1")
                    .should(inner -> inner.term(term -> term.field("public").value(true)))
                    .should(inner -> inner.term(term -> term.field("isPublic").value(true)))));
            if (tags.size() == 1) {
                bool.should(should -> should.term(term -> term.field("orgTag").value(tags.get(0))));
            } else if (!tags.isEmpty()) {
                bool.should(should -> should.bool(tagQuery -> {
                    tagQuery.minimumShouldMatch("1");
                    tags.forEach(tag -> tagQuery.should(inner -> inner.term(term -> term.field("orgTag").value(tag))));
                    return tagQuery;
                }));
            }
            return bool;
        }));
    }

    /**
     * 并行执行两路召回。
     *
     * @param query       查询文本
     * @param queryVector 查询向量
     * @param acl         权限过滤条件
     * @param size        每路召回窗口
     * @param timeout     单路超时
     */
    public RecallResult recallBoth(String query, List<Float> queryVector, Query acl, int size, Duration timeout) {
        CompletableFuture<List<SearchResult>> bm25 = CompletableFuture.supplyAsync(
                () -> safeRecall("BM25", () -> bm25Recall(query, acl, size)), executor);
        CompletableFuture<List<SearchResult>> knn = CompletableFuture.supplyAsync(
                () -> safeRecall("KNN", () -> knnRecall(query, queryVector, acl, size)), executor);
        return new RecallResult(await(bm25, timeout, "BM25"), await(knn, timeout, "KNN"));
    }

    /** 只跑 BM25 路：查询向量生成失败时的降级路径 */
    public List<SearchResult> recallBm25(String query, Query acl, int size, Duration timeout) {
        CompletableFuture<List<SearchResult>> bm25 = CompletableFuture.supplyAsync(
                () -> safeRecall("BM25", () -> bm25Recall(query, acl, size)), executor);
        return await(bm25, timeout, "BM25");
    }

    /** BM25 关键词召回 */
    protected List<SearchResult> bm25Recall(String query, Query acl, int size) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(search -> search
                .index(indexName())
                .query(queryBuilder -> queryBuilder.bool(bool -> bool
                        .must(must -> must.match(match -> match.field(TEXT_FIELD).query(query)))
                        .filter(acl)))
                .source(source -> source.filter(filter -> filter.excludes(VECTOR_FIELD)))
                .size(size), EsDocument.class);
        return toResults(response);
    }

    /** KNN 向量召回；权限条件放进 knn.filter 做前置过滤 */
    protected List<SearchResult> knnRecall(String query, List<Float> queryVector, Query acl, int size) throws Exception {
        SearchResponse<EsDocument> response = esClient.search(search -> search
                .index(indexName())
                .knn(knn -> knn
                        .field(VECTOR_FIELD)
                        .queryVector(queryVector)
                        .k(size)
                        .numCandidates(size)
                        .filter(acl))
                .source(source -> source.filter(filter -> filter.excludes(VECTOR_FIELD)))
                .size(size), EsDocument.class);
        return toResults(response);
    }

    private List<SearchResult> legacyRecall(String query,List<Float> vector,Query acl,int size) throws Exception {
        var response=esClient.search(search -> search.index(indexName())
            .knn(k -> k.field(VECTOR_FIELD).queryVector(vector).k(size).numCandidates(size).filter(acl))
            .query(q -> q.bool(b -> b.filter(acl).must(m -> m.match(t -> t.field(TEXT_FIELD).query(query)))))
            .rescore(r -> r.windowSize(size).query(q -> q.queryWeight(0.2).rescoreQueryWeight(1.0)
                .query(m -> m.match(t -> t.field(TEXT_FIELD).query(query).operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.And)))))
            .source(s -> s.filter(f -> f.excludes(VECTOR_FIELD))).size(size),EsDocument.class);
        return toResults(response);
    }

    /** Typed cold adapter used by Pipeline; preserves failure versus successful empty recall. */
    public reactor.core.publisher.Mono<com.yizhaoqi.smartpai.rag.model.RagTypes.RouteResult> recallDetailed(
            String route,String query,List<Float> vector,Query acl,int size,Duration timeout,
            java.util.function.Predicate<SearchResult> authorized) {
        return reactor.core.publisher.Mono.defer(() -> {
            long started=System.nanoTime();
            return reactor.core.publisher.Mono.fromCallable(() -> {
                var hits=switch(route) {
                    case "bm25" -> bm25Recall(query,acl,size);
                    case "legacy" -> legacyRecall(query,vector,acl,size);
                    default -> knnRecall(query,vector,acl,size);
                };
                var candidates=new ArrayList<com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate>();
                for(SearchResult hit:hits) {
                    if(!authorized.test(hit)) throw new SecurityException("Unauthorized retrieval candidate");
                    if(hit.getCandidateId()==null || hit.getChunkId()==null) throw new IllegalStateException("Candidate identity missing");
                    double score=hit.getScore()==null?0:hit.getScore();
                    if(!Double.isFinite(score)) throw new IllegalStateException("Invalid candidate score");
                    candidates.add(new com.yizhaoqi.smartpai.rag.model.RagTypes.Candidate(hit.getCandidateId(),hit.getFileMd5(),hit.getChunkId(),
                        hit.getUserId(),hit.getFileName(),hit.getTextContent(),score,java.util.Map.of(route,score),List.of(hit.getCandidateId())));
                }
                return new com.yizhaoqi.smartpai.rag.model.RagTypes.RouteResult(route,
                    com.yizhaoqi.smartpai.rag.model.RagTypes.Status.SUCCEEDED,candidates,(System.nanoTime()-started)/1_000_000,null);
            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).timeout(timeout)
            .onErrorResume(error -> {
                if(error instanceof SecurityException) return reactor.core.publisher.Mono.error(error);
                return reactor.core.publisher.Mono.just(new com.yizhaoqi.smartpai.rag.model.RagTypes.RouteResult(route,
                    com.yizhaoqi.smartpai.rag.model.RagTypes.Status.FAILED,List.of(),(System.nanoTime()-started)/1_000_000,error.getClass().getSimpleName()));
            });
        });
    }

    private interface Recall {
        List<SearchResult> run() throws Exception;
    }

    private List<SearchResult> safeRecall(String route, Recall recall) {
        try {
            return recall.run();
        } catch (Exception e) {
            logger.warn("{} 路召回失败，该路按空结果处理: {}", route, e.toString());
            return List.of();
        }
    }

    private List<SearchResult> await(CompletableFuture<List<SearchResult>> future, Duration timeout, String route) {
        Duration effective = (timeout == null || timeout.isZero() || timeout.isNegative()) ? DEFAULT_TIMEOUT : timeout;
        try {
            return future.get(effective.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("{} 路召回超时（{}ms），该路按空结果处理", route, effective.toMillis());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            logger.warn("{} 路召回被中断", route);
        } catch (ExecutionException e) {
            logger.warn("{} 路召回异常，该路按空结果处理: {}", route, e.getCause() == null ? e.toString() : e.getCause().toString());
        }
        return List.of();
    }

    private static List<SearchResult> toResults(SearchResponse<EsDocument> response) {
        if (response == null || response.hits() == null) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>();
        for (Hit<EsDocument> hit : response.hits().hits()) {
            EsDocument source = hit.source();
            if (source == null) {
                continue;
            }
            SearchResult result=new SearchResult(
                    source.getFileMd5(),
                    source.getChunkId(),
                    source.getTextContent(),
                    hit.score() == null ? 0d : hit.score(),
                    source.getUserId(),
                    source.getOrgTag(),
                    source.isPublic());
            result.setCandidateId(hit.index()+":"+hit.id());
            results.add(result);
        }
        return results;
    }
}

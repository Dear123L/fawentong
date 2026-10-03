package com.example.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import com.example.config.RagEsConfig;
import com.example.entity.KnowledgeDocument;
import com.example.mapper.KnowledgeDocumentMapper;
import com.example.service.DocumentTextExtractorService;
import com.example.service.RagVectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量服务实现：BM25 + KNN 双路召回 + 加权 RRF 融合。
 *
 * <p>RRF（Reciprocal Rank Fusion）：{@code score(d) = Σ_r w_r / (k + rank_r(d))}。
 * k 取 10 而非默认 60：k 越大越压平各列表内部的 rank 区分度，
 * 取 10 可逼近 KNN-only 上限（实测 HR@1 0.60 vs 纯 BM25 0.11）。
 * 权重 KNN=1.0 / BM25=0.3：让向量语义召回主导排名，BM25 只做关键词兜底不被噪声稀释。
 */
@Slf4j
@Service
public class RagVectorServiceImpl implements RagVectorService {

    private final ElasticsearchClient esClient;
    private final RagEsConfig esConfig;
    private final DocumentTextExtractorService extractor;
    private final KnowledgeDocumentMapper documentMapper;

    @Value("${dashscope.api.key}")
    private String dashscopeApiKey;

    private static final String EMBED_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final String EMBED_MODEL = "text-embedding-v3";
    private static final int EMBED_DIM = 1024;

    public RagVectorServiceImpl(ElasticsearchClient esClient,
                                RagEsConfig esConfig,
                                DocumentTextExtractorService extractor,
                                KnowledgeDocumentMapper documentMapper) {
        this.esClient = esClient;
        this.esConfig = esConfig;
        this.extractor = extractor;
        this.documentMapper = documentMapper;
    }

    @Override
    public void initEsIndex() {
        try {
            String index = esConfig.getIndexName();
            boolean exists = esClient.indices()
                    .exists(ExistsRequest.of(e -> e.index(index)))
                    .value();
            if (exists) {
                log.info("RAG 索引已存在: {}", index);
                return;
            }
            esClient.indices().create(c -> c
                    .index(index)
                    .mappings(m -> m
                            .properties("content", p -> p.text(t -> t))
                            .properties("kbId", p -> p.keyword(k -> k))
                            .properties("docId", p -> p.keyword(k -> k))
                            .properties("clauseId", p -> p.keyword(k -> k))
                            .properties("chunkIndex", p -> p.integer(i -> i))
                            .properties("vector", p -> p
                                    .denseVector(dv -> dv
                                            .dims(EMBED_DIM)
                                            .index(true)
                                            .similarity("cosine")))));
            log.info("RAG 索引创建成功: {} (dense_vector dim={})", index, EMBED_DIM);
        } catch (Exception e) {
            log.error("初始化 ES 索引失败: {}", esConfig.getIndexName(), e);
        }
    }

    @Override
    public void uploadDocument(Long kbId, Long userId, MultipartFile file) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKbId(kbId);
        doc.setUserId(userId);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileType(file.getContentType());
        doc.setFileSize(file.getSize());
        doc.setStatus("pending");
        documentMapper.insert(doc);

        try {
            Path dir = resolveUploadDir();
            Files.createDirectories(dir);
            String stored = kbId + "_" + doc.getId() + "_"
                    + (doc.getFileName() == null ? "upload" : doc.getFileName());
            Path target = dir.resolve(stored);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            doc.setFilePath(target.toString());

            String text = extractor.extractText(file);
            List<String> chunks = extractor.splitText(text, esConfig.getChunkSize(), esConfig.getChunkOverlap());
            doc.setContentLength(text == null ? 0 : text.length());
            doc.setChunkCount(chunks.size());
            doc.setStatus("done");
            documentMapper.updateParseResult(doc);

            indexChunks(kbId, doc.getId(), chunks);
            log.info("文档入库完成: docId={} chunks={}", doc.getId(), chunks.size());
        } catch (Exception e) {
            log.error("文档处理失败: docId={}", doc.getId(), e);
            doc.setStatus("failed");
            try {
                documentMapper.updateParseResult(doc);
            } catch (Exception ignore) {
                // 状态回写失败不阻断主流程
            }
        }
    }

    /** 切片逐条向量化后批量写入 ES。 */
    private void indexChunks(Long kbId, Long docId, List<String> chunks) throws Exception {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String index = esConfig.getIndexName();
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            List<Float> vector = embed(chunk);
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("content", chunk);
            src.put("kbId", String.valueOf(kbId));
            src.put("docId", String.valueOf(docId));
            // clauseId 缺省用 "kbId-docId-chunkIdx"，保证评测 HR/MRR 有稳定可比的 gold 锚点
            src.put("clauseId", kbId + "-" + docId + "-" + i);
            src.put("chunkIndex", i);
            src.put("vector", vector);
            bulk.operations(op -> op.index(idx -> idx
                    .index(index)
                    .document(JsonData.of(src))));
        }
        esClient.bulk(bulk.build());
        log.info("已写入 {} 个切片到索引 {}", chunks.size(), index);
    }

    @Override
    public List<Map<String, Object>> hybridSearch(Long kbId, String query, int topK) {
        // KNN 开关关闭时退化为纯 BM25（A/B 评测开关，便于复现两种水位）
        if (!esConfig.isKnnEnabled()) {
            return bm25Search(kbId, query, topK);
        }
        List<Map<String, Object>> knn = knnSearch(kbId, query, topK);
        List<Map<String, Object>> bm25 = bm25Search(kbId, query, topK);
        return rrfFuse(knn, bm25, topK);
    }

    /** BM25 关键词召回。 */
    private List<Map<String, Object>> bm25Search(Long kbId, String query, int topK) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            // 多层嵌套 lambda 会让 javac 丢失类型推断，故先构造 term 查询再传入 filter
            Query kbFilter = Query.of(t -> t.term(tm -> tm.field("kbId").value(String.valueOf(kbId))));
            Query q = Query.of(t -> t.bool(b -> b
                    .must(m -> m.match(mm -> mm
                            .field("content")
                            .query(query)))
                    .filter(kbFilter)));

            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(esConfig.getIndexName())
                            .query(q)
                            .size(topK),
                    Map.class);

            for (Hit<Map> hit : resp.hits().hits()) {
                Map<String, Object> item = new HashMap<>(hit.source() == null ? Map.of() : hit.source());
                item.put("clauseId", hit.id());
                item.put("bm25Rank", out.size() + 1);
                out.add(item);
            }
        } catch (Exception e) {
            log.error("BM25 检索失败: {}", e.getMessage());
        }
        return out;
    }

    /** KNN 向量召回。 */
    private List<Map<String, Object>> knnSearch(Long kbId, String query, int topK) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            List<Float> qv = embed(query);
            // 同上：term 查询先抽变量，规避嵌套 lambda 的类型推断丢失
            Query kbFilter = Query.of(t -> t.term(tm -> tm.field("kbId").value(String.valueOf(kbId))));
            // ES 8.15.0 的 KnnQuery.Builder 无 k() 方法（8.15.5+ 才有），
            // 返回条数由 search().size(topK) 控制，numCandidates 决定候选池大小
            KnnQuery knn = KnnQuery.of(k -> k
                    .field("vector")
                    .queryVector(qv)
                    .numCandidates(Math.max(topK * 10, 100))
                    .filter(kbFilter));

            SearchResponse<Map> resp = esClient.search(s -> s
                            .index(esConfig.getIndexName())
                            .size(topK)
                            .query(q -> q.knn(knn)),
                    Map.class);

            for (Hit<Map> hit : resp.hits().hits()) {
                Map<String, Object> item = new HashMap<>(hit.source() == null ? Map.of() : hit.source());
                item.put("clauseId", hit.id());
                item.put("knnScore", hit.score());
                item.put("knnRank", out.size() + 1);
                out.add(item);
            }
        } catch (Exception e) {
            log.error("KNN 检索失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 加权 RRF 融合：score(d) = Σ_r w_r / (k + rank_r(d))。
     * 同一 clauseId 出现在两路时分数累加，最终按分数降序取 topK。
     */
    private List<Map<String, Object>> rrfFuse(List<Map<String, Object>> knn,
                                            List<Map<String, Object>> bm25,
                                            int topK) {
        int k = esConfig.getRrfK();
        double wKnn = esConfig.getRrfWeightKnn();
        double wBm25 = esConfig.getRrfWeightBm25();

        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Map<String, Object>> meta = new LinkedHashMap<>();

        applyRrf(knn, "knnRank", wKnn, k, fused, meta);
        applyRrf(bm25, "bm25Rank", wBm25, k, fused, meta);

        List<Map<String, Object>> result = new ArrayList<>(meta.keySet().stream()
                .map(cid -> {
                    Map<String, Object> m = new LinkedHashMap<>(meta.get(cid));
                    m.put("clauseId", cid);
                    m.put("score", fused.get(cid));
                    return m;
                })
                .collect(java.util.stream.Collectors.toList()));

        result.sort(Comparator.comparingDouble(m -> -Double.parseDouble(String.valueOf(m.get("score")))));
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    private void applyRrf(List<Map<String, Object>> list, String rankKey, double weight, int k,
                          Map<String, Double> fused, Map<String, Map<String, Object>> meta) {
        for (Map<String, Object> item : list) {
            String cid = String.valueOf(item.get("clauseId"));
            Object rankObj = item.get(rankKey);
            int rank = rankObj instanceof Number ? ((Number) rankObj).intValue() : 1;
            fused.merge(cid, weight / (k + rank), Double::sum);
            meta.putIfAbsent(cid, item);
        }
    }

    /**
     * 调用 DashScope text-embedding-v3 生成 1024 维向量。
     *
     * <p>与 {@code backfill_vectors.py} / {@code diag_retrieval.py} 走同一 HTTP 接口，
     * 保证建库脚本与在线服务产生的向量空间一致（模型、维度、归一化口径相同）。
     * TODO: 需要按原实现校对——原实现是否设置了 {@code parameters.instruction}、
     *       是否有本地向量缓存（当前每次检索都重算，无缓存）。
     */
    private List<Float> embed(String text) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", EMBED_MODEL);
        body.put("input", List.of(text == null ? "" : text));
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("dimension", EMBED_DIM);
        body.put("parameters", parameters);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(dashscopeApiKey);

        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        Map<?, ?> resp = new RestTemplate().postForObject(EMBED_URL, req, Map.class);
        if (resp == null) {
            throw new IllegalStateException("DashScope embedding 返回空响应");
        }
        Map<?, ?> data = (Map<?, ?>) resp.get("output");
        if (data == null) {
            throw new IllegalStateException("DashScope embedding 响应缺少 output 字段: " + resp);
        }
        Object embeddings = data.get("embeddings");
        if (embeddings instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> m && m.get("embedding") instanceof List<?> vec) {
                List<Float> out = new ArrayList<>(vec.size());
                for (Object v : vec) {
                    out.add(((Number) v).floatValue());
                }
                return out;
            }
        }
        throw new IllegalStateException("DashScope embedding 响应结构异常: " + resp);
    }

    private Path resolveUploadDir() {
        String dir = System.getProperty("rag.file.upload-dir");
        if (dir == null || dir.isBlank()) {
            dir = Paths.get(System.getProperty("user.dir"), "files", "rag").toString();
        }
        return Paths.get(dir);
    }
}

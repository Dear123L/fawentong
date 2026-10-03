package com.example.service;

import java.util.List;
import java.util.Map;

/**
 * RAG 向量服务：文档入库向量化 + 混合检索（BM25 + KNN 经 RRF 融合）。
 */
public interface RagVectorService {

    /**
     * 初始化 ES 索引（不存在则创建，含 dense_vector 映射）。
     * 应用启动时由 {@code RagController} 的 {@code @PostConstruct} 调用。
     */
    void initEsIndex();

    /**
     * 上传文档：落库 → 解析纯文本 → 切片 → 向量化 → 写入 ES。
     *
     * @param kbId   知识库 ID
     * @param userId 上传者
     * @param file   文件
     */
    void uploadDocument(Long kbId, Long userId, org.springframework.web.multipart.MultipartFile file);

    /**
     * 混合检索：BM25 关键词召回与 KNN 向量召回并行，按加权 RRF 融合排序。
     *
     * @param kbId   知识库 ID
     * @param query  查询串
     * @param topK   返回条数
     * @return 命中的片段列表，每项含 {@code content}/{@code clauseId}/{@code score} 等字段
     */
    List<Map<String, Object>> hybridSearch(Long kbId, String query, int topK);
}

package com.example.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.config.RagEsConfig;
import com.example.mapper.KnowledgeDocumentMapper;
import com.example.service.DocumentTextExtractorService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Step 1 的 clauseId 解析逻辑：优先 canonicalId，回退 _id。
 * 不依赖 ES / DashScope / 密钥，直接对 {@link RagVectorServiceImpl#resolveClauseId} 做单元测试。
 */
public class ClauseIdMappingTest {

    private RagVectorServiceImpl newService() {
        return new RagVectorServiceImpl(
                Mockito.mock(ElasticsearchClient.class),
                Mockito.mock(RagEsConfig.class),
                Mockito.mock(DocumentTextExtractorService.class),
                Mockito.mock(KnowledgeDocumentMapper.class));
    }

    @Test
    void resolveClauseId_prefersCanonicalOverHitId() {
        RagVectorServiceImpl svc = newService();
        Map<String, Object> source = new HashMap<>();
        source.put("canonicalId", "民法典-第585条");
        // 模拟递归拆分子块场景：ES _id 已是 "xxx#1"，canonicalId 仍应保持标准款号
        String resolved = svc.resolveClauseId(source, "民法典-第585条#1");
        assertEquals("民法典-第585条", resolved);
        assertNotEquals("民法典-第585条#1", resolved);
    }

    @Test
    void resolveClauseId_fallsBackToHitIdWhenCanonicalAbsent() {
        RagVectorServiceImpl svc = newService();
        Map<String, Object> source = new HashMap<>();
        source.put("content", "some text");
        // 上传文档未写 canonicalId 时，回退到 ES _id（与旧行为一致）
        String resolved = svc.resolveClauseId(source, "kb-1-2-3");
        assertEquals("kb-1-2-3", resolved);
    }

    @Test
    void resolveClauseId_handlesNullSource() {
        RagVectorServiceImpl svc = newService();
        String resolved = svc.resolveClauseId(null, "hit-id");
        assertEquals("hit-id", resolved);
    }
}

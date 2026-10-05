package com.example.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.config.RagEsConfig;
import com.example.mapper.KnowledgeDocumentMapper;
import com.example.service.ClauseBlock;
import com.example.service.DocumentTextExtractorService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Step 2/3 的条款级切分 + 递归兜底 + canonicalId/_id 派生。
 * 不依赖 ES / 密钥，直接对 {@link DocumentTextExtractorServiceImpl} 与
 * {@link RagVectorServiceImpl#documentIdFor} 做单元测试。
 */
public class ClauseChunkingTest {

    @Test
    void clauseLevelSplitWithRecursiveFallback() {
        DocumentTextExtractorServiceImpl svc = new DocumentTextExtractorServiceImpl();

        StringBuilder longClause = new StringBuilder("第三条 本条款规定如下内容：");
        longClause.append("（一）甲方应当按照约定履行相关义务，不得无故拖延，并且应当保证所提供的服务质量符合国家标准与行业规范的要求。");
        longClause.append("（二）乙方在收到甲方通知后应当及时响应，并在合理期限内完成整改，逾期未整改的应当承担相应违约责任并赔偿损失。");
        longClause.append("（三）双方因履行本协议发生争议的，应当友好协商解决；协商不成的，可以向有管辖权的人民法院提起诉讼解决。");
        while (longClause.length() < 900) {
            longClause.append("为明确双方权利义务，特作如下补充说明：本协议所列各项条款均自双方签字盖章之日起生效，具有同等法律效力，任何一方不得擅自变更或解除。");
        }

        String text = "引言部分说明本协议目的与适用范围。\n"
                + "第一条 甲方应支付货款。\n"
                + "第二条 乙方应按时交货。\n"
                + longClause.toString() + "\n"
                + "第四条 违约责任与争议解决方式。";

        List<ClauseBlock> blocks = svc.splitIntoClauseBlocks(text, "doc99");

        // 块数 = 引言 + 第一 + 第二 + (第三拆3子块) + 第四 = 7
        assertEquals(7, blocks.size(), "块数应为 7");

        // 第三条的 3 个子块共享 canonicalId = doc99-第3条
        List<ClauseBlock> third = blocks.stream()
                .filter(b -> "doc99-第3条".equals(b.getCanonicalId()))
                .collect(Collectors.toList());
        assertEquals(3, third.size(), "第三条应拆成 3 个子块");
        List<Integer> subs = third.stream().map(ClauseBlock::getSubIndex).sorted().collect(Collectors.toList());
        assertEquals(List.of(1, 2, 3), subs, "子块 subIndex 应为 1,2,3");

        // 普通条款 subIndex=0，且 canonicalId 各不相同
        ClauseBlock first = blocks.stream()
                .filter(b -> "doc99-第1条".equals(b.getCanonicalId())).findFirst().orElseThrow();
        assertEquals(0, first.getSubIndex());
        assertEquals("doc99-引言", blocks.get(0).getCanonicalId(), "首块应为引言");
    }

    @Test
    void documentIdForAddsHashSuffixForSubBlocks() {
        RagVectorServiceImpl svc = new RagVectorServiceImpl(
                Mockito.mock(ElasticsearchClient.class),
                Mockito.mock(RagEsConfig.class),
                Mockito.mock(DocumentTextExtractorService.class),
                Mockito.mock(KnowledgeDocumentMapper.class));

        ClauseBlock main = new ClauseBlock("x", "doc99-第3条", 0);
        ClauseBlock sub1 = new ClauseBlock("x", "doc99-第3条", 1);
        ClauseBlock sub2 = new ClauseBlock("x", "doc99-第3条", 2);

        assertEquals("doc99-第3条", svc.documentIdFor(main), "主块 _id = canonicalId");
        assertEquals("doc99-第3条#1", svc.documentIdFor(sub1), "子块 _id 带 #1");
        assertEquals("doc99-第3条#2", svc.documentIdFor(sub2), "子块 _id 带 #2");
    }
}

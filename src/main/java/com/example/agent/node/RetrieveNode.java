package com.example.agent.node;

import com.example.agent.state.AgenticState;
import com.example.service.RagVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 检索节点：从 ES 混合检索（BM25 + KNN 经加权 RRF 融合）取回候选条款片段。
 *
 * <p>返回增量 Map（{@code retrievedDocs}）由框架合入状态——LangGraph4j 的
 * {@code state.data()} 是不可变视图，节点内不可直接写。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieveNode {

    /**
     * 最终期望条数 topK。召回量按 {@code recallSize = max(topK * 2, 50)} 放大：
     * 召回池需显著大于最终产出量，给 GradeNode 的评分门留足筛选空间；
     * 50 为经验兜底，避免 topK 很小时召回过窄而漏掉可用条款。
     */
    private static final int TOP_K = 8;
    private static final int MIN_RECALL = 50;

    private final RagVectorService ragVectorService;

    public CompletableFuture<Map<String, Object>> execute(AgenticState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> updates = new HashMap<>();
            if (!state.isNeedRetrieval()) {
                log.info("检索节点跳过（needRetrieval=false）");
                return updates;
            }
            try {
                int recallSize = Math.max(TOP_K * 2, MIN_RECALL);
                List<Map<String, Object>> docs = ragVectorService.hybridSearch(
                        state.getKbId(), state.getCurrentQuery(), recallSize);
                updates.put("retrievedDocs", docs);
                log.info("检索完成: query={} 召回 {}/{} 条", state.getCurrentQuery(),
                        docs == null ? 0 : docs.size(), recallSize);
            } catch (Exception e) {
                log.error("检索失败: {}", e.getMessage(), e);
                updates.put("retrievedDocs", List.of());
            }
            return updates;
        });
    }
}

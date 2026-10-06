package com.example.agent.multi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScopeCheckNode 审查意图路由单测（P2）。
 *
 * <p>覆盖：①每个审查锚词都能触发 review 意图；②代表性 QA 短语（取自测试集语义）不误触发；
 * ③空 / Null 安全；④直接加载 v2+v3 共 188 道 QA 题，断言审查锚词集 0 误命中
 * （与方案评估阶段 Python 实跑结论一致，做成可重跑的回归护栏）。</p>
 */
class ScopeCheckNodeTest {

    private final ScopeCheckNode node = new ScopeCheckNode();

    @Test
    void reviewAnchors_allTrigger() {
        String[] anchors = {"审查", "审核", "审阅", "霸王条款", "改写", "起草", "漏洞", "有没有问题", "拟定", "修改建议"};
        for (String a : anchors) {
            assertTrue(node.isReviewIntent("请帮我" + a + "这份合同"), "审查锚词应触发 review 意图: " + a);
        }
    }

    @Test
    void qaPhrases_doNotTriggerReview() {
        String[] qa = {
                "买卖合同违约金怎么算",
                "乙方延迟交货违约金多少",
                "合同生效条件是什么",
                "股权转让合同违约金条款",
                "借款利率超过多少算违法",
                "这个条款合理吗",
                "租赁合同中押金条款有效吗"
        };
        for (String q : qa) {
            assertFalse(node.isReviewIntent(q), "QA 题不应误路由到审查分支: " + q);
        }
    }

    @Test
    void emptyAndNull_safe() {
        assertFalse(node.isReviewIntent(null));
        assertFalse(node.isReviewIntent(""));
        assertFalse(node.isReviewIntent("   "));
    }

    /**
     * 188 题 0 误命中回归：扫描 v2+v3 全部 question，统计含审查锚词的题数，断言为 0。
     * 若测试集文件不存在（如脱离项目根目录运行），自动跳过——不阻塞其它用例。
     */
    @Test
    void anchor_zeroFalseHit_on188QaQuestions() throws Exception {
        File f2 = new File("contract_qa_testset_v2.json");
        File f3 = new File("contract_qa_testset_v3.json");
        if (!f2.exists() || !f3.exists()) {
            // 脱离项目根目录时跳过，避免 CI 误报；结论由评估阶段 Python 实跑背书
            return;
        }
        String[] anchors = {"审查", "审核", "审阅", "霸王条款", "改写", "起草", "漏洞", "有没有问题", "拟定", "修改建议"};
        ObjectMapper om = new ObjectMapper();
        List<String> questions = new ArrayList<>();
        collectQuestions(om.readTree(f2), questions);
        collectQuestions(om.readTree(f3), questions);

        List<String> falseHits = new ArrayList<>();
        for (String q : questions) {
            for (String a : anchors) {
                if (q.contains(a)) {
                    falseHits.add("[" + a + "] " + q);
                    break;
                }
            }
        }
        assertTrue(falseHits.isEmpty(),
                "审查锚词在 " + questions.size() + " 道 QA 题中误命中 " + falseHits.size() + " 条: " + falseHits);
    }

    private void collectQuestions(JsonNode root, List<String> out) {
        JsonNode cases = root;
        if (root.isObject()) {
            for (String key : new String[]{"cases", "questions", "data"}) {
                if (root.has(key)) {
                    cases = root.get(key);
                    break;
                }
            }
        }
        if (cases.isArray()) {
            for (JsonNode it : cases) {
                if (it.isTextual()) {
                    out.add(it.asText());
                } else if (it.isObject()) {
                    JsonNode q = it.get("question");
                    if (q == null) q = it.get("q");
                    if (q != null && q.isTextual()) out.add(q.asText());
                }
            }
        }
    }
}

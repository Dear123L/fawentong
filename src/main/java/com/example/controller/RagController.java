package com.example.controller;

import com.example.entity.KnowledgeBase;
import com.example.mapper.KnowledgeBaseMapper;
import com.example.service.MultiAgentRagResult;
import com.example.service.RagAgentService;
import com.example.service.RagChatService;
import com.example.service.RagVectorService;
import com.example.util.SecurityUtils;
import com.example.util.UserContext;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rag")
public class RagController {

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private RagVectorService ragVectorService;

    @Autowired
    private RagChatService ragChatService;

    @Autowired
    private RagAgentService ragAgentService;

    @Autowired
    private com.example.service.MultiAgentRagService multiAgentRagService;

    @PostConstruct
    public void init() {
        ragVectorService.initEsIndex();
    }

    @PostMapping("/kb/create")
    public Map<String, Object> createKnowledgeBase(@RequestBody Map<String, String> params, HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            userId = 1L;
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(params.get("name"));
        kb.setType(params.getOrDefault("type", "private"));
        kb.setUserId(userId);
        kb.setDescription(params.get("description"));

        knowledgeBaseMapper.insert(kb);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", kb);
        return result;
    }

    @GetMapping("/kb/list")
    public Map<String, Object> listKnowledgeBases(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            userId = 1L;
        }

        List<KnowledgeBase> list = knowledgeBaseMapper.selectByUserId(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", list);
        return result;
    }

    @PostMapping("/document/upload")
    public Map<String, Object> uploadDocument(
            @RequestParam("kbId") Long kbId,
            @RequestParam("file") MultipartFile file,
            HttpSession session) throws Exception {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            userId = 1L;
        }

        ragVectorService.uploadDocument(kbId, userId, file);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "上传成功，正在处理中...");
        return result;
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam("kbId") Long kbId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("question") String question,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            userId = 1L;
        }

        return ragChatService.chatStream(userId, kbId, sessionId, question);
    }

    @GetMapping("/chat/history")
    public Map<String, Object> getHistory(
            @RequestParam("sessionId") String sessionId,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            userId = 1L;
        }

        List<Map<String, String>> history = ragChatService.getConversationHistory(userId, sessionId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", history);
        return result;
    }

    /**
     * 智能助手
     */
    @GetMapping("/chatAgent/stream")
    public Map<String, Object> chatAgentStream(
            @RequestParam("kbId") Long kbId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("question") String question,
            HttpSession session) {

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            userId = 1L;
        }

        String answer = ragAgentService.chatStream(userId, kbId, sessionId, question);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", answer);
        return result;
    }

    /**
     * 多智能体问答（范围判定 → 检索/计算 → 综合 → 评审）
     */
    @GetMapping("/chatAgent/multi")
    public Map<String, Object> chatAgentMulti(
            @RequestParam("kbId") Long kbId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("question") String question,
            HttpServletRequest request) {

        Long userId = resolveUserId(request);

        String answer = multiAgentRagService.ask(kbId, sessionId, question, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", answer);
        return result;
    }

    /**
     * 多智能体问答（评测用 debug 端点）：在 /chatAgent/multi 基础上额外回传 5 个评测元数据。
     * 原 /chatAgent/multi 完全不动，生产调用不受影响。仅评测时打此端点。
     */
    @GetMapping("/chatAgent/multiDebug")
    public Map<String, Object> chatAgentMultiDebug(
            @RequestParam("kbId") Long kbId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("question") String question,
            HttpServletRequest request) {

        Long userId = resolveUserId(request);

        MultiAgentRagResult res = multiAgentRagService.askWithMeta(kbId, sessionId, question, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", res.answer());   // 与原接口一致
        result.put("meta", res.meta());     // 仅评测消费
        return result;
    }

    /**
     * A-soft 解析 userId：有合法 token 时优先走 UserContext（openId→DB id，真实身份），
     * 否则回落到 JwtAuthenticationFilter 写入 request 的 userId 属性（无 token 时为 1L）。
     * 这样评测脚本/手签 token（无 DB 用户）也能正常回落，不阻断链路。
     */
    private Long resolveUserId(HttpServletRequest request) {
        Long attr = (Long) request.getAttribute("userId");
        if (SecurityUtils.isLogin()) {
            try {
                return UserContext.getCurrentUserId();
            } catch (Exception e) {
                // DB 无对应用户（如手签评测 token）→ 回落 filter 设置的属性值
                return (attr != null) ? attr : 1L;
            }
        }
        return (attr != null) ? attr : 1L;
    }
}

// AiLegalChatController.java
package com.example.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.dto.CozeChatRequestDto;
import com.example.dto.CozeChatResponseDto;
import com.example.service.IAiLegalChatService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/ai/legal")
public class AiLegalChatController {

    @Autowired
    private IAiLegalChatService aiLegalChatService;

    /**
     * 法律AI对话流式接口
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLegalChat(@RequestBody CozeChatRequestDto request) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30分钟超时
        String sessionId = UUID.randomUUID().toString();

        log.info("开始法律AI流式对话，sessionId: {}, 问题: {}", sessionId, request.getQuestion());

        // 在新线程中处理
        new Thread(() -> {
            try {
                aiLegalChatService.streamLegalChat(request, typedJson -> {
                    try {
                        JSONObject typedChunk = JSON.parseObject(typedJson);
                        String type = typedChunk.getString("type");
                        String text = typedChunk.getString("text");
                        String resultType = typedChunk.getString("resultType");

                        // 构建响应DTO
                        CozeChatResponseDto response = CozeChatResponseDto.builder()
                                .type(type)
                                .text(text)
                                .resultType(resultType)
                                .isEnd(false)
                                .messageId(sessionId)
                                .build();

                        // 发送SSE事件
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(response));

                        log.debug("发送{} chunk: {}", resultType, text);

                    } catch (Exception e) {
                        log.warn("处理typed chunk失败", e);
                    }
                });

                // 对话完成
                sendCompleteEvent(emitter, sessionId);

            } catch (Exception e) {
                log.error("流式对话异常", e);
                sendErrorEvent(emitter, "对话失败: " + e.getMessage(), sessionId);
            }
        }).start();

        // 设置回调
        emitter.onCompletion(() -> {
            log.info("SSE连接完成，sessionId: {}", sessionId);
        });

        emitter.onTimeout(() -> {
            log.warn("SSE连接超时，sessionId: {}", sessionId);
        });

        emitter.onError((throwable) -> {
            log.error("SSE连接错误，sessionId: {}", sessionId, throwable);
        });

        return emitter;
    }

    /**
     * 发送完成事件
     */
    private void sendCompleteEvent(SseEmitter emitter, String sessionId) {
        try {
            CozeChatResponseDto completeResponse = CozeChatResponseDto.builder()
                    .type("complete")
                    .text("")
                    .resultType("complete")
                    .isEnd(true)
                    .messageId(sessionId)
                    .build();

            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(completeResponse));

            emitter.complete();

        } catch (IOException e) {
            log.error("发送完成事件失败", e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 发送错误事件
     */
    private void sendErrorEvent(SseEmitter emitter, String errorMessage, String sessionId) {
        try {
            CozeChatResponseDto errorResponse = CozeChatResponseDto.builder()
                    .type("error")
                    .text(errorMessage)
                    .resultType("error")
                    .isEnd(true)
                    .messageId(sessionId)
                    .build();

            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(errorResponse));

            emitter.complete();

        } catch (IOException e) {
            log.error("发送错误事件失败", e);
            emitter.completeWithError(e);
        }
    }

    /**
     * 简单的测试接口
     */
    @PostMapping("/chat/test")
    public String testChat(@RequestBody CozeChatRequestDto request) {
        StringBuilder fullResponse = new StringBuilder();

        aiLegalChatService.streamLegalChat(request, typedJson -> {
            try {
                JSONObject typedChunk = JSON.parseObject(typedJson);
                String type = typedChunk.getString("type");
                String text = typedChunk.getString("text");

                if ("chunk".equals(type)) {
                    fullResponse.append(text);
                }

            } catch (Exception e) {
                log.warn("处理chunk失败", e);
            }
        });

        return CozeChatResponseDto.builder()
                .type("complete")
                .text(fullResponse.toString())
                .resultType("answer_complete")
                .isEnd(true)
                .messageId(UUID.randomUUID().toString())
                .build()
                .toString();
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public String healthCheck() {
        return "{\"status\":\"UP\",\"service\":\"AI Legal Chat\"}";
    }
}
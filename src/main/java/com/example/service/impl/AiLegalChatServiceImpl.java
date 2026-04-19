// AiLegalChatServiceImpl.java
package com.example.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.dto.CozeChatRequestDto;
import com.example.service.IAiLegalChatService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
public class AiLegalChatServiceImpl implements IAiLegalChatService {

    @Value("${ai.legal.api-key}")
    private String apiKey;

    @Value("${ai.legal.bot-id}")
    private String botId;

    @Value("${ai.legal.url}")
    private String apiUrl;

    private final OkHttpClient client = new OkHttpClient();

    @Override
    public void streamLegalChat(CozeChatRequestDto request, Consumer<String> chunkConsumer) {
        try {
            // 构建请求体
            String requestBody = buildRequestJson(request);
            log.debug("扣子AI请求体: {}", requestBody);

            // 创建HTTP请求
            Request httpRequest = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            // 同步调用API
            try (Response response = client.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("扣子API调用失败: {} - {}", response.code(), response.message());
                    log.error("错误响应体: {}", errorBody);
                    throw new IOException("API调用失败: " + response.code() + " - " + response.message());
                }

                // 处理流式响应
                processStreamResponse(response, chunkConsumer);
            }

        } catch (Exception e) {
            log.error("扣子AI流式调用异常", e);
            throw new RuntimeException("扣子AI服务异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构建扣子API请求JSON
     */
    private String buildRequestJson(CozeChatRequestDto request) {
        // 使用fastjson2构建
        JSONObject json = new JSONObject();
        json.put("bot_id", botId);
        json.put("user_id", "user_" + UUID.randomUUID().toString().substring(0, 8));
        json.put("stream", true);

        // 消息数组
        JSONObject message = new JSONObject();
        message.put("content", request.getQuestion());
        message.put("content_type", "text");
        message.put("role", "user");
        message.put("type", "question");

        JSONArray messages = new JSONArray();
        messages.add(message);
        json.put("additional_messages", messages);

        // 可选参数
        if (request.getConversationId() != null && !request.getConversationId().isEmpty()) {
            json.put("conversation_id", request.getConversationId());
        }

        // 参数配置
        JSONObject parameters = new JSONObject();
        parameters.put("temperature", 0.7);
        parameters.put("max_tokens", 4096);
        json.put("parameters", parameters);

        return json.toJSONString();
    }

    /**
     * 处理流式响应
     */
    private void processStreamResponse(Response response, Consumer<String> chunkConsumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String lineStr = line.trim();
                if (lineStr.isEmpty()) {
                    continue;
                }

                // 处理SSE格式：data: {...}
                if (lineStr.startsWith("data:")) {
                    String dataContent = lineStr.substring(5).trim();

                    // 处理结束标记
                    if ("[DONE]".equals(dataContent)) {
                        log.debug("收到结束标记");
                        continue;
                    }

                    try {
                        // 解析JSON数据
                        JSONObject json = JSON.parseObject(dataContent);
                        String type = json.getString("type");
                        String role = json.getString("role");

                        log.debug("解析到数据，类型: {}, 角色: {}", type, role);

                        // 只处理助手的回答内容
                        if ("answer".equals(type) && "assistant".equals(role)) {
                            String content = json.getString("content");
                            if (content != null && !content.isEmpty()) {
                                // 构建类型化chunk
                                JSONObject typedChunk = new JSONObject();
                                typedChunk.put("type", "chunk");
                                typedChunk.put("text", content);
                                typedChunk.put("resultType", "answer");

                                chunkConsumer.accept(typedChunk.toJSONString());
                                log.debug("发送回答chunk: {}", content);
                            }
                        }
                        // 处理后续问题建议
                        else if ("follow_up".equals(type)) {
                            String content = json.getString("content");
                            if (content != null && !content.isEmpty()) {
                                JSONObject typedChunk = new JSONObject();
                                typedChunk.put("type", "suggestion");
                                typedChunk.put("text", content);
                                typedChunk.put("resultType", "follow_up");

                                chunkConsumer.accept(typedChunk.toJSONString());
                                log.debug("发送建议chunk: {}", content);
                            }
                        }
                        // 处理verbose消息
                        else if ("verbose".equals(type)) {
                            log.debug("收到verbose消息");
                        }

                    } catch (Exception e) {
                        log.warn("解析data内容失败: {}", dataContent, e);
                    }
                }
                // 处理事件行
                else if (lineStr.startsWith("event:")) {
                    String eventName = lineStr.substring(6).trim();
                    log.debug("收到事件: {}", eventName);

                    if ("conversation.chat.completed".equals(eventName)) {
                        log.info("对话完成事件");
                    }
                }
            }

            log.info("流式响应处理完成");

        } catch (Exception e) {
            log.error("处理流式响应异常", e);
            throw e;
        }
    }
}
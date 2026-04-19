// IAiLegalChatService.java
package com.example.service;

import com.example.dto.CozeChatRequestDto;

import java.util.function.Consumer;

public interface IAiLegalChatService {

    /**
     * 流式法律对话
     * @param request 请求参数
     * @param chunkConsumer 块数据消费者
     */
    void streamLegalChat(CozeChatRequestDto request, Consumer<String> chunkConsumer);
}
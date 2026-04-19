package com.example.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CozeChatResponseDto {
    /**
     * 消息类型：chunk（流式片段）、complete（完整响应）、error（错误）
     */
    private String type;
    
    /**
     * 消息内容
     */
    private String text;
    
    /**
     * 结果类型（区分不同的处理逻辑）
     */
    private String resultType;
    
    /**
     * 是否结束标志
     */
    @Builder.Default
    private Boolean isEnd = false;
    
    /**
     * 消息ID
     */
    private String messageId;
}
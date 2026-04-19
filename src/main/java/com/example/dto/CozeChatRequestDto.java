// CozeChatRequestDto.java
package com.example.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class CozeChatRequestDto {
    /**
     * 会话ID，用于标识同一用户的多次对话
     */
    private String conversationId;

    /**
     * 用户输入的问题
     */
    @NotBlank(message = "问题不能为空")
    private String question;

    /**
     * 请求类型（可选）
     */
    private String requestType;

    /**
     * 是否流式输出
     */
    private Boolean stream = true;

    /**
     * 附加消息列表
     */
    private List<AdditionalMessage> additionalMessages;

    @Data
    public static class AdditionalMessage {
        private String content;
        private String contentType = "text";
        private String role = "user";
        private String type = "question";
    }
}
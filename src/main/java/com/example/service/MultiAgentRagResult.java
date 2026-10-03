package com.example.service;

import java.util.Map;

/**
 * 多智能体问答返回体（评测用）。
 * answer 供生产消费；meta 携带 5 个评测元数据：
 *   retrieved / extracted / computed_penalty / rejected / replanned。
 */
public record MultiAgentRagResult(String answer, Map<String, Object> meta) {
}

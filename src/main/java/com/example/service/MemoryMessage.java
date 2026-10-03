package com.example.service;

/**
 * 一条对话记忆（短期记忆的最小单元）。
 * role 取值：user / assistant。
 */
public record MemoryMessage(String role, String content, long timestamp) {
}

package com.example.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户长期画像（P1b 长期记忆）。
 *
 * <p>对应 Redis Hash {@code user:profile:{uid}} 的字段：
 *  - contracts：用户上传/提及的合同列表（如 "买卖合同""借款合同"）。
 *  - preferences：用户偏好（键值对，如 "回答风格"->"简洁"）。
 *  - freqQA：高频关心的问题及对应条款类型（{q, clause}）。
 *  - corrections：纠错反馈（{wrong, right}），即用户纠正过系统的地方。
 *  - lastActive：最后活跃时间（ISO 时间戳字符串）。</p>
 *
 * <p>本对象只是「数据载体」，不负责持久化；读写由 {@link UserMemoryService} 实现。</p>
 */
public class UserMemoryProfile {

    /** 上传/提及的合同列表 */
    public List<String> contracts = new ArrayList<>();

    /** 用户偏好键值对 */
    public Map<String, String> preferences = new HashMap<>();

    /** 高频关心的问题及条款类型：{q, clause} */
    public List<Map<String, String>> freqQA = new ArrayList<>();

    /** 纠错反馈：{wrong, right} */
    public List<Map<String, String>> corrections = new ArrayList<>();

    /** 最后活跃时间（ISO 时间戳） */
    public String lastActive;

    /** 画像是否为空（无任何信号）。 */
    public boolean isEmpty() {
        return (contracts == null || contracts.isEmpty())
                && (preferences == null || preferences.isEmpty())
                && (freqQA == null || freqQA.isEmpty())
                && (corrections == null || corrections.isEmpty());
    }
}

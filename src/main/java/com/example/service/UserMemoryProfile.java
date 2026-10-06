package com.example.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户长期画像（P1b 长期记忆）。
 *
 * <p>对应 Redis Hash {@code user:profile:{uid}} 的字段：
 *  - topics：用户咨询过的法律领域或文件类型（如 "买卖合同""离婚诉讼""劳动工伤"）。
 *  - preferences：用户偏好（键值对，如 "回答风格"-&gt;"简洁"）。
 *  - freqQA：高频关心的问题及对应条款类型（{q, clause}）。
 *  - reviewHistory：审查过的条款类型与风险类型（{clause, risk}），
 *    仅合同审查链路产生，问答链路为空。
 *  - corrections：纠错反馈（{wrong, right}），即用户纠正过系统的地方。
 *  - lastActive：最后活跃时间（ISO 时间戳字符串），<b>当前仅记录不参与逻辑</b>，
 *    预留给画像衰减策略（长期未活跃时降低召回权重）。</p>
 *
 * <p>字段迁移：原 {@code contracts} 字段（仅合同语义）已更名为 {@code topics}
 * 并扩展为法律领域。读取时若 {@code topics} 为空而 {@code contracts} 有值，
 * 会沿用旧值并回写新字段，见实现类的兼容处理。</p>
 *
 * <p>本对象只是「数据载体」，不负责持久化；读写由 {@link UserMemoryService} 实现。</p>
 */
public class UserMemoryProfile {

    /** 咨询过的法律领域或文件类型（承接原 contracts 字段） */
    public List<String> topics = new ArrayList<>();

    /** 审查过的条款类型与风险类型：{clause, risk}；仅审查链路产生 */
    public List<Map<String, String>> reviewHistory = new ArrayList<>();

    /** 用户偏好键值对 */
    public Map<String, String> preferences = new HashMap<>();

    /** 高频关心的问题及条款类型：{q, clause}（法域中立，不限于合同） */
    public List<Map<String, String>> freqQA = new ArrayList<>();

    /** 纠错反馈：{wrong, right} */
    public List<Map<String, String>> corrections = new ArrayList<>();

    /** 最后活跃时间（ISO 时间戳）。当前仅记录，不参与召回或衰减计算。 */
    public String lastActive;

    /** 画像是否为空（无任何信号）。 */
    public boolean isEmpty() {
        return (topics == null || topics.isEmpty())
                && (preferences == null || preferences.isEmpty())
                && (freqQA == null || freqQA.isEmpty())
                && (reviewHistory == null || reviewHistory.isEmpty())
                && (corrections == null || corrections.isEmpty());
    }
}

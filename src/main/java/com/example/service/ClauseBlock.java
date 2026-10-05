package com.example.service;

/**
 * 条款级切分后的一个内容块。
 *
 * <p>同一条款（含递归兜底拆出的子块）共享同一个 {@link #canonicalId}，
 * 仅 {@link #subIndex} 不同（主块=0，子块从 1 递增）。这保证检索返回/评测匹配时
 * 用 {@code canonicalId} 即可命中整条，不受拆子块影响。
 */
public class ClauseBlock {

    private final String content;
    private final String canonicalId;
    private final int subIndex;

    public ClauseBlock(String content, String canonicalId, int subIndex) {
        this.content = content;
        this.canonicalId = canonicalId;
        this.subIndex = subIndex;
    }

    public String getContent() {
        return content;
    }

    public String getCanonicalId() {
        return canonicalId;
    }

    public int getSubIndex() {
        return subIndex;
    }
}

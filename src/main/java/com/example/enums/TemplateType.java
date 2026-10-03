package com.example.enums;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 模板类型枚举。
 *
 * <p>下载地址由「相对路径」+「服务对外 base-url」在运行时拼接，
 * base-url 通过配置 {@code APP_BASE_URL} 注入，避免把服务器 IP 硬编码进源码。
 * <p>枚举无法由 Spring 容器实例化，故 base-url 由 {@code PostServiceImpl} 初始化时
 * 通过 {@link #setBaseUrl(String)} 静态注入。
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)  // 让枚举序列化为对象而不是名称
public enum TemplateType {
    RENTAL_CONTRACT(1, "租房合同模板.docx", "/static/file/房屋租赁合同模板.docx"),
    LABOR_DISPUTE_ANSWER(2, "劳动争议答辩状.docx", "/static/file/劳动争议答辩状.docx"),
    LABOR_DISPUTE_INDICTMENT(3, "劳动争议起诉状.docx", "/static/file/劳动争议起诉状.docx"),

    RENTAL_DISPUTE_ANSWER(4, "房屋租赁合同纠纷答辩状.docx", "/static/file/房屋租赁合同纠纷答辩状.docx"),
    RENTAL_DISPUTE_INDICTMENT(5, "房屋租赁合同纠纷起诉状.docx", "/static/file/房屋租赁合同纠纷起诉状.docx"),

    LOAN_DISPUTE_ANSWER(6, "民间借贷纠纷答辩状.docx", "/static/file/民间借贷纠纷答辩状.docx"),
    LOAN_DISPUTE_INDICTMENT(7, "民间借贷纠纷起诉状.docx", "/static/file/民间借贷纠纷起诉状.docx");

    /** 服务对外可访问地址，由 {@code APP_BASE_URL} 注入；枚举无法直接注入，故走静态字段 + setter。 */
    private static String baseUrl = "http://localhost:8080";

    private final Integer fileId;
    private final String fileName;
    /** 相对路径（不含 base-url），便于多环境部署。 */
    private final String relativePath;

    TemplateType(Integer fileId, String fileName, String relativePath) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.relativePath = relativePath;
    }

    /**
     * 由 Spring 注入 {@code file.base-url}（同 {@code APP_BASE_URL}）。
     * <p>枚举实例不由容器管理，无法用 {@code @Value} 直接注入，故提供静态 setter，
     * 在 {@code PostServiceImpl} 构造/初始化时调用一次。
     */
    public static void setBaseUrl(String url) {
        if (url != null && !url.isBlank()) {
            baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    // 根据数字代码获取枚举
    public static TemplateType getByFileId(Integer fileId) {
        for (TemplateType type : values()) {
            if (type.getFileId().equals(fileId)) {
                return type;
            }
        }
        throw new IllegalArgumentException("无效的模板类型代码: " + fileId);
    }

    // Getter 方法
    public Integer getFileId() { return fileId; }
    public String getFileName() { return fileName; }
    public String getRelativePath() { return relativePath; }

    /** 完整下载地址 = base-url + 相对路径。 */
    public String getDownloadUrl() { return baseUrl + relativePath; }
}

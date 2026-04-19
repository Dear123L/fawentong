package com.example.enums;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 模板类型枚举
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)  // 让枚举序列化为对象而不是名称
public enum TemplateType {
    RENTAL_CONTRACT(1, "租房合同模板.docx", "http://REDACTED_APP_HOST:8080/static/file/房屋租赁合同模板.docx"),
    LABOR_DISPUTE_ANSWER(2, "劳动争议答辩状.docx", "http://REDACTED_APP_HOST:8080/static/file/劳动争议答辩状.docx"),
    LABOR_DISPUTE_INDICTMENT(3, "劳动争议起诉状.docx", "http://REDACTED_APP_HOST:8080/static/file/劳动争议起诉状.docx"),

    RENTAL_DISPUTE_ANSWER(4, "房屋租赁合同纠纷答辩状.docx", "http://REDACTED_APP_HOST:8080/static/file/房屋租赁合同纠纷答辩状.docx"),
    RENTAL_DISPUTE_INDICTMENT(5, "房屋租赁合同纠纷起诉状.docx", "http://REDACTED_APP_HOST:8080/static/file/房屋租赁合同纠纷起诉状.docx"),

    LOAN_DISPUTE_ANSWER(6, "民间借贷纠纷答辩状.docx", "http://REDACTED_APP_HOST:8080/static/file/民间借贷纠纷答辩状.docx"),
    LOAN_DISPUTE_INDICTMENT(7, "民间借贷纠纷起诉状.docx", "http://REDACTED_APP_HOST:8080/static/file/民间借贷纠纷起诉状.docx");

    private final Integer fileId;
    private final String fileName;
    private final String downloadUrl;

    TemplateType(Integer fileId, String fileName, String downloadUrl) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.downloadUrl = downloadUrl;
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
    public String getDownloadUrl() { return downloadUrl; }
}
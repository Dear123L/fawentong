package com.example.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * @author lhh
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DepositRefundRequestDTO {

    // 收函方信息
    private String recipientName;   // 姓名/公司名称
    private String recipientId;     // 身份证号/统一社会信用代码
    private String recipientAddress;
    private String recipientPhone;

    // 发函方信息
    private String senderName;
    private String senderId;
    private String senderAddress;
    private String senderPhone;

    // 合同信息
    private String contractYear;    // 合同年
    private String contractMonth;   // 合同月
    private String contractDay;     // 合同日
    private String contractName;    // 合同名称
    private String contractNumber;  // 合同编号
    private BigDecimal depositAmount;
    private String depositAmountChinese; // 大写金额

    // 合同终止原因
    private String terminationType; // "按期终止"、"协议解除"、"其他原因"

    // 按期终止的具体日期
    private String terminationYear1;
    private String terminationMonth1;
    private String terminationDay1;

    // 协议解除的约定返还日期
    private String terminationYear2;
    private String terminationMonth2;
    private String terminationDay2;

    private String otherReason;    // 其他原因说明

    // 返还要求
    private Integer deadlineDays;  // 要求返还期限

    // 收款账户信息
    private String bankAccountName;
    private String bankName;
    private String bankAccountNumber;

    // 发函日期 - 使用String，直接填入模板
    private String sendDate;       // 例如："2024年01月15日"

    // 附件清单
    private List<String> attachments;

    // 方法：格式化为占位符
    public String getContractYearPlaceholder() {
        return isNotEmpty(contractYear) ? contractYear : "____";
    }

    public String getContractMonthPlaceholder() {
        return isNotEmpty(contractMonth) ? String.format("%02d", parseToInt(contractMonth)) : "__";
    }

    public String getContractDayPlaceholder() {
        return isNotEmpty(contractDay) ? String.format("%02d", parseToInt(contractDay)) : "__";
    }

    // 格式化日期的方法
    public String getFormattedContractDate() {
        return formatDate(contractYear, contractMonth, contractDay);
    }

    public String getFormattedTerminationDate1() {
        return formatDate(terminationYear1, terminationMonth1, terminationDay1);
    }

    public String getFormattedTerminationDate2() {
        return formatDate(terminationYear2, terminationMonth2, terminationDay2);
    }

    // 发函日期拆分（如果需要分开显示）
    public String getSendYear() {
        if (!isNotEmpty(sendDate)) return "____";
        // 尝试从"2024年01月15日"提取年份
        return extractYear(sendDate);
    }

    public String getSendMonth() {
        if (!isNotEmpty(sendDate)) return "__";
        // 尝试从"2024年01月15日"提取月份
        return extractMonth(sendDate);
    }

    public String getSendDay() {
        if (!isNotEmpty(sendDate)) return "__";
        // 尝试从"2024年01月15日"提取日
        return extractDay(sendDate);
    }

    // 辅助方法
    private String formatDate(String year, String month, String day) {
        if (isNotEmpty(year) && isNotEmpty(month) && isNotEmpty(day)) {
            return year + "年" + month + "月" + day + "日";
        } else if (isNotEmpty(year) && isNotEmpty(month)) {
            return year + "年" + month + "月____日";
        } else if (isNotEmpty(year)) {
            return year + "年____月____日";
        } else {
            return "________年____月____日";
        }
    }

    private String extractYear(String dateStr) {
        // 从"2024年01月15日"提取"2024"
        try {
            return dateStr.split("年")[0];
        } catch (Exception e) {
            return "____";
        }
    }

    private String extractMonth(String dateStr) {
        // 从"2024年01月15日"提取"01"
        try {
            String afterYear = dateStr.split("年")[1];
            return afterYear.split("月")[0];
        } catch (Exception e) {
            return "__";
        }
    }

    private String extractDay(String dateStr) {
        // 从"2024年01月15日"提取"15"
        try {
            String afterMonth = dateStr.split("月")[1];
            return afterMonth.replace("日", "");
        } catch (Exception e) {
            return "__";
        }
    }

    private boolean isNotEmpty(String str) {
        return str != null && !str.trim().isEmpty();
    }

    private int parseToInt(String str) {
        try {
            return Integer.parseInt(str.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}

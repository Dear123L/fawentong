
package com.example.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 消费者协会投诉书请求DTO
 */
@Data
public class ConsumerComplaintRequestDTO {
    
    // 投诉人信息
    private String complainantName;           // 姓名
    private String complainantPhone;          // 联系电话
    private String complainantId;             // 身份证号
    private String complainantAddress;        // 联系地址
    private String complainantEmail;          // 电子邮箱
    
    // 被投诉人信息
    private String businessName;              // 商家名称
    private String businessCreditCode;        // 统一社会信用代码
    private String businessAddress;           // 经营地址
    private String businessPhone;             // 联系电话
    private String businessPersonInCharge;    // 负责人
    
    // 投诉内容概述
    private String purchaseYear;              // 购买年份
    private String purchaseMonth;             // 购买月份
    private String purchaseDay;               // 购买日期
    private String purchaseLocation;          // 购买地点
    private String productServiceName;        // 商品/服务名称
    private String productServiceDetail;      // 商品规格/服务详情
    private BigDecimal totalPrice;            // 总费用
    private String totalPriceChinese;         // 总费用大写
    private String paymentMethod;             // 付款方式
    private String receiptNumber;             // 凭证编号
    private String complaintReason;           // 投诉原因
    
    // 详细情况描述 - 购买/接受服务过程
    private String serviceYear;               // 服务年份
    private String serviceMonth;              // 服务月份
    private String serviceDay;                // 服务日期
    private String serviceHour;               // 服务时间
    private String purchaseMethod;            // 购买方式
    private BigDecimal purchasePrice;         // 购买价格
    private String businessCommitment;        // 商家承诺
    private Integer serviceCompletionDays;    // 服务完成天数
    private String communicationContent;      // 沟通内容
    
    // 详细情况描述 - 发现问题过程
    private String discoveryYear;             // 发现年份
    private String discoveryMonth;            // 发现月份
    private String discoveryDay;              // 发现日期
    private String discoveryHour;             // 发现时间
    private String problemDescription;        // 问题描述
    private String sceneDescription;          // 具体场景
    
    // 详细情况描述 - 尝试解决问题过程
    private String firstContactYear;          // 首次联系年份
    private String firstContactMonth;         // 首次联系月份
    private String firstContactDay;           // 首次联系日期
    private String firstContactHour;          // 首次联系时间
    private String contactMethod;             // 沟通方式
    private String firstResponse;             // 首次回应
    private String subsequentDate1;           // 后续联系日期1
    private String subsequentDate2;           // 后续联系日期2
    private String subsequentContactMethod;   // 后续沟通方式
    private String subsequentResponse;        // 后续回应处理
    
    // 经济损失及赔偿要求
    private BigDecimal economicLoss;          // 经济损失
    private String economicLossChinese;       // 经济损失大写
    private BigDecimal compensationRequest;   // 要求赔偿经济损失
    private BigDecimal mentalDamages;         // 精神损害抚慰金
    
    // 附件
    private String attachmentList;            // 附件清单
    
    // 投诉日期
    private String complaintDate;             // 投诉日期
    
    // 获取格式化日期方法
    public String getFormattedPurchaseDate() {
        return purchaseYear + "年" + purchaseMonth + "月" + purchaseDay + "日";
    }
    
    public String getFormattedServiceDate() {
        return serviceYear + "年" + serviceMonth + "月" + serviceDay + "日";
    }
    
    public String getFormattedDiscoveryDate() {
        return discoveryYear + "年" + discoveryMonth + "月" + discoveryDay + "日";
    }
    
    public String getFormattedFirstContactDate() {
        return firstContactYear + "年" + firstContactMonth + "月" + firstContactDay + "日";
    }
}
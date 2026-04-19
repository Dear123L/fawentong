package com.example.service.impl;

import com.example.dto.ConsumerComplaintRequestDTO;
import com.example.dto.DepositRefundRequestDTO;
import com.example.service.DocumentService;
import com.example.util.MoneyUtil;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.util.Base64;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档生成服务实现类
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    @Override
    public byte[] generateDepositRefundLetter(DepositRefundRequestDTO requestDTO) throws IOException {
        try {
            // 1. 读取模板文件
            ClassPathResource templateResource = new ClassPathResource("static/file/要求返还押金函.docx");
            InputStream templateStream = templateResource.getInputStream();

            // 2. 处理Word文档
            XWPFDocument document = new XWPFDocument(templateStream);

            // 3. 准备替换数据
            Map<String, String> replacements = prepareReplacements(requestDTO);

            // 4. 替换所有段落中的占位符
            replaceInParagraphs(document, replacements);

            // 5. 替换表格中的占位符
            replaceInTables(document, replacements);

            // 6. 输出为字节数组
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.write(baos);
            document.close();
            templateStream.close();

            return baos.toByteArray();

        } catch (Exception e) {
            throw new IOException("生成文档失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String generateBase64Document(DepositRefundRequestDTO requestDTO) throws IOException {
        byte[] documentBytes = generateDepositRefundLetter(requestDTO);
        return Base64.getEncoder().encodeToString(documentBytes);    }

    @Override
    public String getFileName(DepositRefundRequestDTO requestDTO) {
        return buildFileName(requestDTO);
    }

    /**
     * 准备替换数据
     */
    private Map<String, String> prepareReplacements(DepositRefundRequestDTO requestDTO) {
        Map<String, String> replacements = new HashMap<>();

        // 函件信息
//        replacements.put("${letterSerial}", getValueOrDefault(requestDTO.getLetterSerial(), ""));
//        replacements.put("${letterNumber}", getValueOrDefault(requestDTO.getLetterNumber(), ""));

        // 收函方信息
        replacements.put("${recipientName}", getValueOrDefault(requestDTO.getRecipientName()));
        replacements.put("${recipientId}", getValueOrDefault(requestDTO.getRecipientId()));
        replacements.put("${recipientAddress}", getValueOrDefault(requestDTO.getRecipientAddress()));
        replacements.put("${recipientPhone}", getValueOrDefault(requestDTO.getRecipientPhone()));

        // 发函方信息
        replacements.put("${senderName}", getValueOrDefault(requestDTO.getSenderName()));
        replacements.put("${senderId}", getValueOrDefault(requestDTO.getSenderId()));
        replacements.put("${senderAddress}", getValueOrDefault(requestDTO.getSenderAddress()));
        replacements.put("${senderPhone}", getValueOrDefault(requestDTO.getSenderPhone()));

        // 合同信息
        replacements.put("${contractYear}", requestDTO.getContractYearPlaceholder());
        replacements.put("${contractMonth}", requestDTO.getContractMonthPlaceholder());
        replacements.put("${contractDay}", requestDTO.getContractDayPlaceholder());
        replacements.put("${contractName}", getValueOrDefault(requestDTO.getContractName(), "合同/协议"));
        replacements.put("${contractNumber}", getValueOrDefault(requestDTO.getContractNumber()));

        // 押金金额
        if (requestDTO.getDepositAmount() != null) {
            replacements.put("${depositAmount}", requestDTO.getDepositAmount().toString());
        } else {
            replacements.put("${depositAmount}", "______");
        }

        // 金额大写
        String amountChinese;
        if (StringUtils.hasText(requestDTO.getDepositAmountChinese())) {
            amountChinese = requestDTO.getDepositAmountChinese();
        } else if (requestDTO.getDepositAmount() != null) {
            amountChinese = MoneyUtil.convertToChinese(requestDTO.getDepositAmount());
        } else {
            amountChinese = "________________________元整";
        }
        replacements.put("${depositAmountChinese}", amountChinese);

        String terminationTypeChinese = "";
        if ("按期终止".equals(requestDTO.getTerminationType())) {
            terminationTypeChinese = "一";
        } else if ("协议解除".equals(requestDTO.getTerminationType())) {
            terminationTypeChinese = "二";
        } else if ("其他原因".equals(requestDTO.getTerminationType())) {
            terminationTypeChinese = "三";
        } else {
            terminationTypeChinese = "____"; // 默认占位符
        }
        replacements.put("${terminationTypeChinese}", terminationTypeChinese);

        // 合同终止原因
        String terminationContent = buildTerminationContent(requestDTO);
        replacements.put("${terminationReasonContent}", terminationContent);

        // 返还要求
        if (requestDTO.getDeadlineDays() != null) {
            replacements.put("${deadlineDays}", requestDTO.getDeadlineDays().toString());
        } else {
            replacements.put("${deadlineDays}", "____");
        }

        // 收款账户
        replacements.put("${bankAccountName}", getValueOrDefault(requestDTO.getBankAccountName()));
        replacements.put("${bankName}", getValueOrDefault(requestDTO.getBankName()));
        replacements.put("${bankAccountNumber}", getValueOrDefault(requestDTO.getBankAccountNumber()));

        // 发函日期
        String sendDate = getSendDate(requestDTO);
        replacements.put("${sendYear}", extractYear(sendDate));
        replacements.put("${sendMonth}", extractMonth(sendDate));
        replacements.put("${sendDay}", extractDay(sendDate));

        // 发函人名称
        replacements.put("${senderNameSign}", getValueOrDefault(requestDTO.getSenderName()));

        // 附件清单
        String contractName = getValueOrDefault(requestDTO.getContractName(), "______");
        replacements.put("${attachments}", contractName);

        return replacements;
    }

    /**
     * 构建合同终止原因内容 - 使用Word换行
     */
    private String buildTerminationContent(DepositRefundRequestDTO requestDTO) {
        StringBuilder content = new StringBuilder();

        if ("按期终止".equals(requestDTO.getTerminationType())) {
            content.append("\r\n");
            content.append("☑前述合同/协议已于");
            content.append(requestDTO.getFormattedTerminationDate1());
            content.append("按期终止/解除，且我方已完全履行合同项下全部义务，不存在任何违约行为。");

            // Word换行：在字符串中保持正常，然后在替换时处理段落
            content.append("\r\n\r\n"); // Windows换行符

            content.append("□我方与您/贵方已通过《解除协议》一致同意解除原合同，并约定您/贵方应在________年____月____日前返还上述押金。");
            content.append("\r\n\r\n");
            content.append("□其他情况：________________________________________________________。");
        }
        else if ("协议解除".equals(requestDTO.getTerminationType())) {
            content.append("\r\n");
            content.append("□前述合同/协议已于________年____月____日按期终止/解除，且我方已完全履行合同项下全部义务，不存在任何违约行为。");
            content.append("\r\n\r\n");

            content.append("☑我方与您/贵方已通过《解除协议》一致同意解除原合同，并约定您/贵方应在");
            content.append(requestDTO.getFormattedTerminationDate2());
            content.append("前返还上述押金。");
            content.append("\r\n\r\n");

            content.append("□其他情况：________________________________________________________。");
        }
        else if ("其他原因".equals(requestDTO.getTerminationType())) {
            content.append("\r\n");
            content.append("□前述合同/协议已于________年____月____日按期终止/解除，且我方已完全履行合同项下全部义务，不存在任何违约行为。");
            content.append("\r\n\r\n");
            content.append("□我方与您/贵方已通过《解除协议》一致同意解除原合同，并约定您/贵方应在________年____月____日前返还上述押金。");
            content.append("\r\n\r\n");

            content.append("☑其他情况：");
            if (StringUtils.hasText(requestDTO.getOtherReason())) {
                content.append(requestDTO.getOtherReason());
            } else {
                content.append("________________________________________________________");
            }
            content.append("。");
        }
        else {
            // 默认全部未选中
            content.append("\r\n");
            content.append("□前述合同/协议已于________年____月____日按期终止/解除，且我方已完全履行合同项下全部义务，不存在任何违约行为。");
            content.append("\r\n\r\n");
            content.append("□我方与您/贵方已通过《解除协议》一致同意解除原合同，并约定您/贵方应在________年____月____日前返还上述押金。");
            content.append("\r\n\r\n");
            content.append("□其他情况：________________________________________________________。");
        }

        return content.toString();
    }

    /**
     * 获取发函日期
     */
    private String getSendDate(DepositRefundRequestDTO requestDTO) {
        if (StringUtils.hasText(requestDTO.getSendDate())) {
            return requestDTO.getSendDate();
        } else {
            // 默认今天
            return LocalDate.now().format(DATE_FORMATTER);
        }
    }

    /**
     * 提取年份
     */
    private String extractYear(String dateStr) {
        try {
            return dateStr.split("年")[0];
        } catch (Exception e) {
            return "____";
        }
    }

    /**
     * 提取月份
     */
    private String extractMonth(String dateStr) {
        try {
            String afterYear = dateStr.split("年")[1];
            String month = afterYear.split("月")[0];
            // 确保两位数字
            if (month.length() == 1) {
                return "0" + month;
            }
            return month;
        } catch (Exception e) {
            return "__";
        }
    }

    /**
     * 提取日期
     */
    private String extractDay(String dateStr) {
        try {
            String afterMonth = dateStr.split("月")[1];
            String day = afterMonth.replace("日", "");
            // 确保两位数字
            if (day.length() == 1) {
                return "0" + day;
            }
            return day;
        } catch (Exception e) {
            return "__";
        }
    }

    /**
     * 替换段落中的占位符
     */
    private void replaceInParagraphs(XWPFDocument document, Map<String, String> replacements) {
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText();
            if (text != null && text.contains("$")) {
                String newText = replacePlaceholders(text, replacements);
                if (!text.equals(newText)) {
                    // 清空原有内容
                    for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
                        paragraph.removeRun(i);
                    }
                    // 添加新内容
                    XWPFRun run = paragraph.createRun();
                    run.setText(newText);
                    run.setFontFamily("宋体");
                }
            }
        }
    }

    /**
     * 替换表格中的占位符
     */
    private void replaceInTables(XWPFDocument document, Map<String, String> replacements) {
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph paragraph : cell.getParagraphs()) {
                        String text = paragraph.getText();
                        if (text != null && text.contains("$")) {
                            String newText = replacePlaceholders(text, replacements);
                            if (!text.equals(newText)) {
                                // 清空原有内容
                                for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) {
                                    paragraph.removeRun(i);
                                }
                                // 添加新内容
                                XWPFRun run = paragraph.createRun();
                                run.setText(newText);
                                run.setFontFamily("宋体");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 替换文本中的占位符
     */
    private String replacePlaceholders(String text, Map<String, String> replacements) {
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            if (entry.getValue() != null) {
                text = text.replace(entry.getKey(), entry.getValue());
            }
        }
        return text;
    }

    /**
     * 构建文件名
     */
    private String buildFileName(DepositRefundRequestDTO requestDTO) {
        StringBuilder fileName = new StringBuilder("要求返还押金函");

        if (StringUtils.hasText(requestDTO.getRecipientName())) {
            fileName.append("_").append(requestDTO.getRecipientName());
        }

        if (StringUtils.hasText(requestDTO.getContractName())) {
            fileName.append("_").append(requestDTO.getContractName());
        }

        fileName.append("_").append(System.currentTimeMillis()).append(".docx");

        // 文件名长度限制和非法字符过滤
        return fileName.toString()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
    }

    private String getValueOrDefault(String value) {
        return getValueOrDefault(value, "");
    }

    private String getValueOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    @Override
    public byte[] generateConsumerComplaintLetter(ConsumerComplaintRequestDTO requestDTO) throws IOException {
        try {
            // 1. 读取模板文件
            ClassPathResource templateResource = new ClassPathResource("static/file/消费者协会投诉书模板.docx");
            InputStream templateStream = templateResource.getInputStream();

            // 2. 处理Word文档
            XWPFDocument document = new XWPFDocument(templateStream);

            // 3. 准备替换数据
            Map<String, String> replacements = prepareComplaintReplacements(requestDTO);

            // 4. 替换所有段落中的占位符
            replaceInParagraphs(document, replacements);

            // 5. 替换表格中的占位符
            replaceInTables(document, replacements);

            // 6. 输出为字节数组
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.write(baos);
            document.close();
            templateStream.close();

            return baos.toByteArray();

        } catch (Exception e) {
            throw new IOException("生成消费者协会投诉书失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取消费者协会投诉书文件名 - Controller中已统一使用英文文件名，此方法保留备用
     */
    @Override
    public String getConsumerComplaintFileName(ConsumerComplaintRequestDTO requestDTO) {
        return "consumer_complaint_" + System.currentTimeMillis() + ".docx";
    }

    /**
     * 准备消费者投诉书替换数据
     */
    private Map<String, String> prepareComplaintReplacements(ConsumerComplaintRequestDTO dto) {
        Map<String, String> replacements = new HashMap<>();

        // 投诉人信息
        replacements.put("${complainantName}", getValueOrDefault(dto.getComplainantName()));
        replacements.put("${complainantPhone}", getValueOrDefault(dto.getComplainantPhone()));
        replacements.put("${complainantId}", getValueOrDefault(dto.getComplainantId()));
        replacements.put("${complainantAddress}", getValueOrDefault(dto.getComplainantAddress()));
        replacements.put("${complainantEmail}", getValueOrDefault(dto.getComplainantEmail()));

        // 被投诉人信息
        replacements.put("${businessName}", getValueOrDefault(dto.getBusinessName()));
        replacements.put("${businessCreditCode}", getValueOrDefault(dto.getBusinessCreditCode()));
        replacements.put("${businessAddress}", getValueOrDefault(dto.getBusinessAddress()));
        replacements.put("${businessPhone}", getValueOrDefault(dto.getBusinessPhone()));
        replacements.put("${businessPersonInCharge}", getValueOrDefault(dto.getBusinessPersonInCharge()));

        // 投诉内容概述 - 日期
        replacements.put("${purchaseYear}", getValueOrDefault(dto.getPurchaseYear(), "____"));
        replacements.put("${purchaseMonth}", getValueOrDefault(dto.getPurchaseMonth(), "__"));
        replacements.put("${purchaseDay}", getValueOrDefault(dto.getPurchaseDay(), "__"));

        // 投诉内容概述 - 其他
        replacements.put("${purchaseLocation}", getValueOrDefault(dto.getPurchaseLocation()));
        replacements.put("${productServiceName}", getValueOrDefault(dto.getProductServiceName()));
        replacements.put("${productServiceDetail}", getValueOrDefault(dto.getProductServiceDetail()));

        // 金额处理
        if (dto.getTotalPrice() != null) {
            replacements.put("${totalPrice}", dto.getTotalPrice().toString());
        } else {
            replacements.put("${totalPrice}", "______");
        }

        String totalPriceChinese;
        if (StringUtils.hasText(dto.getTotalPriceChinese())) {
            totalPriceChinese = dto.getTotalPriceChinese();
        } else if (dto.getTotalPrice() != null) {
            totalPriceChinese = MoneyUtil.convertToChinese(dto.getTotalPrice());
        } else {
            totalPriceChinese = "________________________元整";
        }
        replacements.put("${totalPriceChinese}", totalPriceChinese);

        replacements.put("${paymentMethod}", getValueOrDefault(dto.getPaymentMethod()));
        replacements.put("${receiptNumber}", getValueOrDefault(dto.getReceiptNumber()));
        replacements.put("${complaintReason}", getValueOrDefault(dto.getComplaintReason()));

        // 详细情况描述 - 购买/接受服务过程
        replacements.put("${serviceYear}", getValueOrDefault(dto.getServiceYear(), "____"));
        replacements.put("${serviceMonth}", getValueOrDefault(dto.getServiceMonth(), "__"));
        replacements.put("${serviceDay}", getValueOrDefault(dto.getServiceDay(), "__"));
        replacements.put("${serviceHour}", getValueOrDefault(dto.getServiceHour(), "__"));
        replacements.put("${purchaseMethod}", getValueOrDefault(dto.getPurchaseMethod()));

        if (dto.getPurchasePrice() != null) {
            replacements.put("${purchasePrice}", dto.getPurchasePrice().toString());
        } else {
            replacements.put("${purchasePrice}", "______");
        }

        // 商家承诺，包含服务完成天数
        String businessCommitment = buildBusinessCommitment(dto);
        replacements.put("${businessCommitment}", businessCommitment);

        // 服务完成天数占位符 - 如果存在则填入，否则保留原占位符
        if (dto.getServiceCompletionDays() != null) {
            replacements.put("${serviceCompletionDays}", dto.getServiceCompletionDays().toString());
        } else {
            replacements.put("${serviceCompletionDays}", "____");
        }

        replacements.put("${communicationContent}", getValueOrDefault(dto.getCommunicationContent()));

        // 详细情况描述 - 发现问题过程
        replacements.put("${discoveryYear}", getValueOrDefault(dto.getDiscoveryYear(), "____"));
        replacements.put("${discoveryMonth}", getValueOrDefault(dto.getDiscoveryMonth(), "__"));
        replacements.put("${discoveryDay}", getValueOrDefault(dto.getDiscoveryDay(), "__"));
        replacements.put("${discoveryHour}", getValueOrDefault(dto.getDiscoveryHour(), "__"));
        replacements.put("${problemDescription}", getValueOrDefault(dto.getProblemDescription()));
        replacements.put("${sceneDescription}", getValueOrDefault(dto.getSceneDescription()));

        // 详细情况描述 - 尝试解决问题过程
        replacements.put("${firstContactYear}", getValueOrDefault(dto.getFirstContactYear(), "____"));
        replacements.put("${firstContactMonth}", getValueOrDefault(dto.getFirstContactMonth(), "__"));
        replacements.put("${firstContactDay}", getValueOrDefault(dto.getFirstContactDay(), "__"));
        replacements.put("${firstContactHour}", getValueOrDefault(dto.getFirstContactHour(), "__"));

        // ⚠️ 注意：原模板中有两处字段顺序错误，这里进行修正
        // 原模板：对方回应为${contactMethod}（如拒绝处理...）
        // 正确应为：对方回应为${firstResponse}，沟通方式为${contactMethod}
        replacements.put("${contactMethod}", getValueOrDefault(dto.getContactMethod()));
        replacements.put("${firstResponse}", getValueOrDefault(dto.getFirstResponse()));

        replacements.put("${subsequentDate1}", getValueOrDefault(dto.getSubsequentDate1(), "____年__月__日"));
        replacements.put("${subsequentDate2}", getValueOrDefault(dto.getSubsequentDate2(), "____月__日"));
        replacements.put("${subsequentContactMethod}", getValueOrDefault(dto.getSubsequentContactMethod()));
        replacements.put("${subsequentResponse}", getValueOrDefault(dto.getSubsequentResponse()));

        // 经济损失及赔偿要求
        if (dto.getEconomicLoss() != null) {
            replacements.put("${economicLoss}", dto.getEconomicLoss().toString());
        } else {
            replacements.put("${economicLoss}", "______");
        }

        String economicLossChinese;
        if (StringUtils.hasText(dto.getEconomicLossChinese())) {
            economicLossChinese = dto.getEconomicLossChinese();
        } else if (dto.getEconomicLoss() != null) {
            economicLossChinese = MoneyUtil.convertToChinese(dto.getEconomicLoss());
        } else {
            economicLossChinese = "________________________元整";
        }
        replacements.put("${economicLossChinese}", economicLossChinese);

        if (dto.getCompensationRequest() != null) {
            replacements.put("${compensationRequest}", dto.getCompensationRequest().toString());
        } else {
            replacements.put("${compensationRequest}", "______");
        }

        if (dto.getMentalDamages() != null) {
            replacements.put("${mentalDamages}", dto.getMentalDamages().toString());
        } else {
            replacements.put("${mentalDamages}", "______");
        }

        // 附件清单
        String attachmentList = buildAttachmentList(dto);
        replacements.put("${attachmentList}", attachmentList);

        // 签名与日期
        String complaintDate = getComplaintDate(dto);
        replacements.put("${complaintDate}", complaintDate);

        return replacements;
    }

    /**
     * 构建商家承诺内容
     */
    private String buildBusinessCommitment(ConsumerComplaintRequestDTO dto) {
        StringBuilder commitment = new StringBuilder();

        if (StringUtils.hasText(dto.getBusinessCommitment())) {
            commitment.append(dto.getBusinessCommitment());

            // 如果包含服务完成天数的占位符且有具体天数，替换为具体值
            if (dto.getServiceCompletionDays() != null) {
                String temp = commitment.toString();
                temp = temp.replace("${serviceCompletionDays}", dto.getServiceCompletionDays().toString());
                commitment = new StringBuilder(temp);
            }
        } else {
            commitment.append("如商品质保1年、7天无理由退货");
            if (dto.getServiceCompletionDays() != null) {
                commitment.append("、服务在").append(dto.getServiceCompletionDays()).append("日内完成等");
            } else {
                commitment.append("、服务在____日内完成等");
            }
        }

        return commitment.toString();
    }

    /**
     * 构建附件清单
     */
    private String buildAttachmentList(ConsumerComplaintRequestDTO dto) {
        if (StringUtils.hasText(dto.getAttachmentList())) {
            return dto.getAttachmentList();
        }

        // 默认附件清单
        StringBuilder attachments = new StringBuilder();
        attachments.append("1. 购买凭证复印件（收据/发票/支付记录）\n");
        attachments.append("2. 商品/服务问题照片或视频\n");
        attachments.append("3. 与商家沟通记录截图\n");
        attachments.append("4. 商家承诺相关证明材料\n");

        return attachments.toString();
    }

    /**
     * 获取投诉日期
     */
    private String getComplaintDate(ConsumerComplaintRequestDTO dto) {
        if (StringUtils.hasText(dto.getComplaintDate())) {
            return dto.getComplaintDate();
        } else {
            return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        }
    }

}
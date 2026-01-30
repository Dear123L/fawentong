package com.example.service.impl;

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
        replacements.put("${letterSerial}", getValueOrDefault(requestDTO.getLetterSerial(), ""));
        replacements.put("${letterNumber}", getValueOrDefault(requestDTO.getLetterNumber(), ""));

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
            content.append("□前述合同/协议已于________年____月____日按期终止/解除，且我方已完全履行合同项下全部义务，不存在任何违约行为。");
            content.append("\r\n\r\n");

            content.append("☑我方与您/贵方已通过《解除协议》一致同意解除原合同，并约定您/贵方应在");
            content.append(requestDTO.getFormattedTerminationDate2());
            content.append("前返还上述押金。");
            content.append("\r\n\r\n");

            content.append("□其他情况：________________________________________________________。");
        }
        else if ("其他原因".equals(requestDTO.getTerminationType())) {
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
}
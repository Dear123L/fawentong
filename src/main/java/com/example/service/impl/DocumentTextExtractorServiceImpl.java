package com.example.service.impl;

import com.example.service.DocumentTextExtractorService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档文本抽取实现。
 *
 * <p>支持 txt/md/csv（按 UTF-8 直读）与 docx（POI XWPF 抽正文）。
 *
 * <p>与 {@link com.example.service.DocumentConverterService} 职责不同：那条链路用
 * PDFBox + POI 把 pdf/doc 转成<b>图片</b>（供合同审查、模板预览），不做文本抽取。
 * 若要支持 pdf 文本抽取，可复用 PDFBox 的 PDFTextStripper。
 */
@Slf4j
@Service
public class DocumentTextExtractorServiceImpl implements DocumentTextExtractorService {

    @Override
    public String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try (InputStream in = file.getInputStream()) {
            if (name.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(in);
                     XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                    return extractor.getText();
                }
            }
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // TODO: 需要按原实现校对——doc（老格式）与 pdf 分支待补
            log.warn("暂不支持的文件类型, 按纯文本处理: {}", name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("文档文本抽取失败: {}", name, e);
            return "";
        }
    }

    /**
     * 固定长度切分 + 重叠。
     *
     * <p>步长 = size - overlap，保证相邻切片有 overlap 字符的重叠，
     * 避免一条完整条款正好被切断后语义割裂。
     * TODO: 需要按原实现校对——原实现为朴素定长切分（面试中已作为"硬伤"记录：
     *      未按条款/段落语义边界切），此处保持一致以便复现既有评测水位。
     */
    @Override
    public List<String> splitText(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank() || size <= 0) {
            return chunks;
        }
        int step = Math.max(1, size - Math.max(0, overlap));
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + size);
            String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }
}

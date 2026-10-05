package com.example.service.impl;

import com.example.service.ClauseBlock;
import com.example.service.DocumentTextExtractorService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * 条款级切分：按"第X条"切成独立条款，超长条款（&gt;800 字）递归拆子块。
     *
     * <p>双校验防误切：① 条号后须跟全角空格/句边界（。；：、，））；② 条号须处在
     * 预期递增序列内（相邻跳变 &gt;3 视为误切丢弃）。同一条款的所有子块共享 canonicalId，
     * 仅 subIndex 不同（主块=0，子块从 1 递增），保证检索/评测按 canonicalId 命中整条。
     */
    @Override
    public List<ClauseBlock> splitIntoClauseBlocks(String text, String sourceLabel) {
        List<ClauseBlock> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String label = sourceLabel == null || sourceLabel.isBlank() ? "doc" : sourceLabel;

        // 1) 定位所有"第X条"起点
        Matcher m = CLAUSE_PATTERN.matcher(text);
        List<int[]> hits = new ArrayList<>();
        while (m.find()) {
            int after = m.end();
            boolean boundary = after >= text.length()
                    || "　 。；：、，）)".indexOf(text.charAt(after)) >= 0
                    || Character.isWhitespace(text.charAt(after));
            if (!boundary) {
                continue;
            }
            int no = parseClauseNo(m.group(1));
            hits.add(new int[]{m.start(), no});
        }

        if (hits.isEmpty()) {
            // 无条款结构：整段作为一条，canonicalId 用全文标记
            out.add(new ClauseBlock(text.trim(), label + "-全文", 0));
            return out;
        }

        // 2) 递增序列校验：跳变过大（>3）视为误切跳过
        List<int[]> valid = new ArrayList<>();
        int prev = -100;
        for (int[] h : hits) {
            int no = h[1];
            if (prev >= 0 && no - prev > 3) {
                continue;
            }
            valid.add(h);
            prev = no;
        }
        if (valid.isEmpty()) {
            out.add(new ClauseBlock(text.trim(), label + "-全文", 0));
            return out;
        }

        // 3) 引言（第一条款前的文本）单独成块
        int firstStart = valid.get(0)[0];
        if (firstStart > 0) {
            String intro = text.substring(0, firstStart).trim();
            if (!intro.isEmpty()) {
                out.add(new ClauseBlock(intro, label + "-引言", 0));
            }
        }

        // 4) 逐条款切块
        for (int i = 0; i < valid.size(); i++) {
            int[] h = valid.get(i);
            int start = h[0];
            int end = (i + 1 < valid.size()) ? valid.get(i + 1)[0] : text.length();
            String clauseText = text.substring(start, end).trim();
            if (clauseText.isEmpty()) {
                continue;
            }
            String canonicalId = label + "-第" + h[1] + "条";
            if (clauseText.length() > MAX_CLAUSE_LEN) {
                List<String> subs = recursiveSplit(clauseText);
                for (int k = 0; k < subs.size(); k++) {
                    out.add(new ClauseBlock(subs.get(k).trim(), canonicalId, k + 1));
                }
            } else {
                out.add(new ClauseBlock(clauseText, canonicalId, 0));
            }
        }
        return out;
    }

    /** 递归兜底：长条款按 （一）（二）… 或 ；。 拆子块；仍过长则按 800 字硬切。 */
    private List<String> recursiveSplit(String clause) {
        List<String> out = new ArrayList<>();
        Matcher mm = SUB_PATTERN.matcher(clause);
        List<Integer> marks = new ArrayList<>();
        while (mm.find()) {
            marks.add(mm.start());
        }
        if (marks.isEmpty()) {
            for (String s : clause.split("[；。]")) {
                if (!s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        } else {
            for (int i = 0; i < marks.size(); i++) {
                int start = marks.get(i);
                int end = (i + 1 < marks.size()) ? marks.get(i + 1) : clause.length();
                out.add(clause.substring(start, end).trim());
            }
        }
        if (out.size() == 1 && out.get(0).length() > MAX_CLAUSE_LEN) {
            out.clear();
            for (int i = 0; i < clause.length(); i += MAX_CLAUSE_LEN) {
                out.add(clause.substring(i, Math.min(clause.length(), i + MAX_CLAUSE_LEN)));
            }
        }
        return out;
    }

    /** 把"第X条"中的条号解析为阿拉伯数字（支持 零~千 中文数字与阿拉伯数字）。 */
    private static int parseClauseNo(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        if (s.matches("[0-9]+")) {
            return Integer.parseInt(s);
        }
        Map<Character, Integer> map = new HashMap<>();
        map.put('零', 0); map.put('一', 1); map.put('二', 2); map.put('三', 3); map.put('四', 4);
        map.put('五', 5); map.put('六', 6); map.put('七', 7); map.put('八', 8); map.put('九', 9);
        map.put('十', 10); map.put('百', 100); map.put('千', 1000);
        int result = 0, temp = 0;
        for (int i = 0; i < s.length(); i++) {
            Integer v = map.get(s.charAt(i));
            if (v == null) {
                return 0;
            }
            if (v < 10) {
                temp = v;
            } else {
                if (temp == 0) {
                    temp = 1;
                }
                result += temp * v;
                temp = 0;
            }
        }
        result += temp;
        return result;
    }

    private static final Pattern CLAUSE_PATTERN =
            Pattern.compile("第\\s*([0-9零一二三四五六七八九十百千]+)\\s*条");
    private static final Pattern SUB_PATTERN =
            Pattern.compile("（([一二三四五六七八九十]+)）");
    private static final int MAX_CLAUSE_LEN = 800;
}

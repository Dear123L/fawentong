package com.example.service.impl;

import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.entity.RiskPoint;
import com.example.service.AiAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;

@Slf4j
@Service
public class DashScopeAiAnalysisServiceImpl implements AiAnalysisService {

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.api.model:qwen-max}")
    private String model;

    @Override
    public List<RiskPoint> analyzeContract(String contractText) {
        try {
            log.info("=== AI分析开始 ===");

            if (!StringUtils.hasText(apiKey)) {
                throw new RuntimeException("DashScope API key未配置，请在application.yml中配置 dashscope.api.key");
            }

            if (!StringUtils.hasText(contractText) || contractText.trim().length() < 10) {
                log.warn("合同文本过短: {}", contractText.length());
                throw new IllegalArgumentException("合同文本内容不足，无法分析");
            }

            log.info("AI模型: {}, 文本长度: {}", model, contractText.length());

            // 1. 构建提示词
            String prompt = buildPrompt(contractText);
            log.debug("提示词长度: {}", prompt.length());

            // 2. 调用AI接口
            String response = callDashScopeApi(prompt);
            log.debug("AI原始响应: {}", response);

            // 3. 解析响应
            List<RiskPoint> riskPoints = parseAiResponse(response);

            log.info("=== AI分析完成，发现 {} 个风险点 ===", riskPoints.size());
            return riskPoints;

        } catch (Exception e) {
            log.error("AI分析失败", e);
            throw new RuntimeException("AI分析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建提示词
     */
    private String buildPrompt(String contractText) {
        // 限制文本长度，避免token超限
        String limitedText;
        if (contractText.length() > 8000) {
            limitedText = contractText.substring(0, 8000) + "...（文本过长，已截断）";
            log.warn("合同文本过长，已截断至8000字符");
        } else {
            limitedText = contractText;
        }

        return "你是一个专业的法律合同审查专家，请分析以下租房合同文本，找出其中的法律风险点并给出具体建议。\n\n" +
                "【合同文本】\n" + limitedText + "\n\n" +
                "【风险审查要点】\n" +
                "1. 合同主体：出租方是否为产权人或有无转租授权\n" +
                "2. 租金押金：押金条款是否明确，扣除标准是否合理\n" +
                "3. 维修责任：维修时限是否明确\n" +
                "4. 违约责任：违约金是否合理公平\n" +
                "5. 转租条款：转租权限是否合理\n" +
                "6. 费用承担：各项费用是否明确\n\n" +
                "【输出要求】\n" +
                "请严格按照以下JSON数组格式返回，不要有任何其他文字：\n" +
                "[\n" +
                "  {\n" +
                "    \"riskNumber\": \"风险点1\",\n" +
                "    \"chapter\": \"具体条款位置（如：第一条房屋基本情况）\",\n" +
                "    \"originalText\": \"相关合同原文片段\",\n" +
                "    \"riskWarning\": \"详细的风险说明和具体建议\"\n" +
                "  }\n" +
                "]\n\n" +
                "【注意事项】\n" +
                "1. 如果没有风险点，返回空数组：[]\n" +
                "2. 最多返回6个风险点\n" +
                "3. 风险说明要具体、实用\n" +
                "4. JSON格式必须正确";
    }

    /**
     * 调用 DashScope API，使用 Generation 接口。
     */
    private String callDashScopeApi(String prompt) throws Exception {
        try {
            log.info("调用DashScope API，模型: {}", model);

            Generation gen = new Generation();

            // 构建用户消息
            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            // 构建请求参数
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .messages(List.of(userMsg))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            // 发送请求
            long startTime = System.currentTimeMillis();
            GenerationResult result = gen.call(param);
            long endTime = System.currentTimeMillis();

            log.info("DashScope API调用耗时: {}ms", endTime - startTime);

            // 检查结果
            if (result == null) {
                throw new RuntimeException("AI接口返回空结果");
            }

            if (result.getOutput() == null) {
                throw new RuntimeException("AI接口输出为空");
            }

            if (result.getOutput().getChoices() == null || result.getOutput().getChoices().isEmpty()) {
                throw new RuntimeException("AI接口无有效选项");
            }

            // 获取响应内容
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();

            if (content == null || content.trim().isEmpty()) {
                throw new RuntimeException("AI接口返回内容为空");
            }

            log.debug("AI响应内容长度: {}", content.length());
            return content;

        } catch (ApiException e) {
            log.error("DashScope API调用异常", e);
            throw new RuntimeException("AI服务调用失败: " + e.getMessage());
        } catch (NoApiKeyException e) {
            log.error("API密钥错误", e);
            throw new RuntimeException("API密钥配置错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("调用AI接口未知异常", e);
            throw new RuntimeException("AI服务调用异常: " + e.getMessage());
        }
    }

    /**
     * 解析AI响应
     */
    private List<RiskPoint> parseAiResponse(String response) {
        try {
            log.info("开始解析AI响应");

            // 清理响应文本
            String cleanedResponse = response.trim();

            // 提取JSON部分
            String jsonStr = extractJsonFromResponse(cleanedResponse);
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                log.error("无法从响应中提取JSON，响应内容: {}", cleanedResponse);
                throw new RuntimeException("AI响应格式错误，无法提取JSON");
            }

            log.debug("提取的JSON: {}", jsonStr);

            // 验证JSON格式
            if (!isValidJsonArray(jsonStr)) {
                log.error("无效的JSON数组格式: {}", jsonStr);
                throw new RuntimeException("AI返回的JSON格式无效");
            }

            // 解析JSON
            JSONArray jsonArray = JSON.parseArray(jsonStr);
            List<RiskPoint> riskPoints = new ArrayList<>();

            for (int i = 0; i < jsonArray.size(); i++) {
                try {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    RiskPoint risk = new RiskPoint();

                    String riskNumber = obj.getString("riskNumber");
                    risk.setRiskNumber(riskNumber != null ? riskNumber : "风险点" + (i + 1));

                    String chapter = obj.getString("chapter");
                    risk.setChapter(chapter != null ? chapter : "未明确章节");

                    String originalText = obj.getString("originalText");
                    risk.setOriginalText(originalText != null ? originalText : "相关合同条款");

                    String riskWarning = obj.getString("riskWarning");
                    risk.setRiskWarning(riskWarning != null ? riskWarning : "存在潜在风险，建议仔细审查");

                    riskPoints.add(risk);
                    log.info("解析到风险点 {}: {}", i + 1, risk.getRiskNumber());

                } catch (Exception e) {
                    log.warn("解析第{}个风险点时出错: {}", i + 1, e.getMessage());
                }
            }

            log.info("成功解析{}个风险点", riskPoints.size());
            return riskPoints;

        } catch (Exception e) {
            log.error("解析AI响应失败", e);
            throw new RuntimeException("解析AI响应失败: " + e.getMessage());
        }
    }

    /**
     * 从响应中提取JSON
     */
    private String extractJsonFromResponse(String response) {
        try {
            if (response == null || response.trim().isEmpty()) {
                return null;
            }

            // 清理可能的markdown代码块标记
            String cleaned = response
                    .replace("```json", "")
                    .replace("```JSON", "")
                    .replace("```", "")
                    .trim();

            // 查找第一个'['和最后一个']'
            int start = cleaned.indexOf('[');
            int end = cleaned.lastIndexOf(']');

            if (start >= 0 && end > start) {
                String jsonStr = cleaned.substring(start, end + 1);

                // 简单验证JSON括号匹配
                int openBrackets = countChar(jsonStr, '[');
                int closeBrackets = countChar(jsonStr, ']');
                int openBraces = countChar(jsonStr, '{');
                int closeBraces = countChar(jsonStr, '}');

                if (openBrackets == closeBrackets && openBraces == closeBraces) {
                    return jsonStr;
                }
            }

            // 如果没有找到数组，尝试找对象
            start = cleaned.indexOf('{');
            end = cleaned.lastIndexOf('}');

            if (start >= 0 && end > start) {
                String jsonStr = cleaned.substring(start, end + 1);
                // 如果是单个对象，包装成数组
                if (jsonStr.startsWith("{") && jsonStr.endsWith("}")) {
                    return "[" + jsonStr + "]";
                }
            }

            return null;

        } catch (Exception e) {
            log.warn("提取JSON失败: {}", e.getMessage());
            return null;
        }
    }

    private int countChar(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }

    /**
     * 验证JSON数组格式
     */
    private boolean isValidJsonArray(String jsonStr) {
        try {
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                return false;
            }

            String trimmed = jsonStr.trim();
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                return false;
            }

            // 尝试解析
            JSON.parseArray(trimmed);
            return true;

        } catch (Exception e) {
            log.warn("JSON验证失败: {}", e.getMessage());
            return false;
        }
    }
}
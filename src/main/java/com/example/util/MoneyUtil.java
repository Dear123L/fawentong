package com.example.util;

import java.math.BigDecimal;

/**
 * 金额转换工具类
 */
public class MoneyUtil {
    
    private static final String[] CN_NUMBERS = {"零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"};
    private static final String[] CN_UNIT = {"", "拾", "佰", "仟"};
    private static final String[] CN_BIG_UNIT = {"", "万", "亿"};
    
    /**
     * 将数字金额转换为中文大写金额
     */
    public static String convertToChinese(BigDecimal amount) {
        if (amount == null) {
            return "零元整";
        }
        
        // 处理负数
        boolean isNegative = amount.compareTo(BigDecimal.ZERO) < 0;
        amount = amount.abs();
        
        String strAmount = amount.setScale(2, BigDecimal.ROUND_HALF_UP).toString();
        String[] parts = strAmount.split("\\.");
        String integerPart = parts[0];
        String decimalPart = parts.length > 1 ? parts[1] : "00";
        
        // 处理整数部分
        StringBuilder chinese = new StringBuilder();
        
        // 处理整数部分（每4位一组）
        int groupCount = (integerPart.length() + 3) / 4;
        for (int i = 0; i < groupCount; i++) {
            int start = Math.max(0, integerPart.length() - (i + 1) * 4);
            int end = integerPart.length() - i * 4;
            String group = integerPart.substring(start, end);
            
            String groupChinese = convertGroup(group);
            if (!groupChinese.isEmpty()) {
                chinese.insert(0, groupChinese + CN_BIG_UNIT[i]);
            }
        }
        
        if (chinese.length() == 0) {
            chinese.append("零");
        }
        chinese.append("元");
        
        // 处理小数部分
        if ("00".equals(decimalPart)) {
            chinese.append("整");
        } else {
            int jiao = Integer.parseInt(decimalPart.substring(0, 1));
            int fen = decimalPart.length() > 1 ? Integer.parseInt(decimalPart.substring(1, 2)) : 0;
            
            if (jiao > 0) {
                chinese.append(CN_NUMBERS[jiao]).append("角");
            }
            if (fen > 0) {
                if (jiao == 0) {
                    chinese.append("零");
                }
                chinese.append(CN_NUMBERS[fen]).append("分");
            }
        }
        
        // 添加负号
        if (isNegative) {
            chinese.insert(0, "负");
        }
        
        return chinese.toString();
    }
    
    private static String convertGroup(String group) {
        StringBuilder result = new StringBuilder();
        boolean lastZero = true;
        
        for (int i = 0; i < group.length(); i++) {
            int digit = group.charAt(i) - '0';
            int unitIndex = group.length() - i - 1;
            
            if (digit == 0) {
                if (!lastZero && i != group.length() - 1) {
                    result.append(CN_NUMBERS[0]);
                    lastZero = true;
                }
            } else {
                result.append(CN_NUMBERS[digit]).append(CN_UNIT[unitIndex]);
                lastZero = false;
            }
        }
        
        // 去除末尾的零
        if (result.length() > 0 && result.charAt(result.length() - 1) == CN_NUMBERS[0].charAt(0)) {
            result.deleteCharAt(result.length() - 1);
        }
        
        return result.toString();
    }
}
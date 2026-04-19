// 2. 创建实现类：DocumentConverterServiceImpl.java
package com.example.service.impl;

import com.example.service.DocumentConverterService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DocumentConverterServiceImpl implements DocumentConverterService {

    @Override
    public boolean isSupportedFile(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null) return false;
        
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") || 
               lowerName.endsWith(".docx");
    }

    @Override
    public List<byte[]> convertToImages(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename();
        String extension = getFileExtension(fileName);
        
        log.info("开始转换文档为图片: {}", fileName);
        
        if (extension.equalsIgnoreCase(".pdf")) {
            return convertPdfToImages(file);
        } else if (extension.equalsIgnoreCase(".doc") || extension.equalsIgnoreCase(".docx")) {
            return convertWordToImages(file);
        } else {
            throw new IllegalArgumentException("不支持的文档格式: " + extension);
        }
    }
    
    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".")).toLowerCase();
    }

    /**
     * 将PDF转换为图片
     */
    private List<byte[]> convertPdfToImages(MultipartFile file) throws Exception {
        List<byte[]> imageBytesList = new ArrayList<>();
        
        try (InputStream is = file.getInputStream();
             PDDocument document = PDDocument.load(is)) {
            
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            
            log.info("PDF文档页数: {}", pageCount);
            
            for (int page = 0; page < pageCount; page++) {
                try {
                    // 渲染PDF页面为图片
                    BufferedImage image = renderer.renderImageWithDPI(page, 150);
                    
                    // 转换为字节数组
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "PNG", baos);
                    byte[] imageBytes = baos.toByteArray();
                    
                    imageBytesList.add(imageBytes);
                    
                    log.debug("PDF第 {} 页转换完成，图片大小: {} bytes", page + 1, imageBytes.length);
                    
                } catch (Exception e) {
                    log.error("转换PDF第 {} 页失败: {}", page + 1, e.getMessage());
                    throw e;
                }
            }
            
        } catch (Exception e) {
            log.error("PDF转换失败", e);
            throw new RuntimeException("PDF文档转换失败: " + e.getMessage());
        }
        
        return imageBytesList;
    }

    /**
     * 将Word文档转换为图片
     */
    private List<byte[]> convertWordToImages(MultipartFile file) throws Exception {
        List<byte[]> imageBytesList = new ArrayList<>();
        
        try (InputStream is = file.getInputStream();
             XWPFDocument document = new XWPFDocument(is)) {
            
            // 提取Word内容
            List<String> pages = extractWordContent(document);
            log.info("Word文档提取到 {} 页内容", pages.size());
            
            if (pages.isEmpty()) {
                pages.add("Word文档内容为空");
            }
            
            // 为每页创建图片
            for (int i = 0; i < pages.size(); i++) {
                String pageContent = pages.get(i);
                BufferedImage image = createTextImage(pageContent, i + 1);
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "PNG", baos);
                byte[] imageBytes = baos.toByteArray();
                
                imageBytesList.add(imageBytes);
                log.debug("Word第 {} 页转换完成", i + 1);
            }
            
        } catch (Exception e) {
            log.error("Word转换失败", e);
            throw new RuntimeException("Word文档转换失败: " + e.getMessage());
        }
        
        return imageBytesList;
    }
    
    /**
     * 提取Word内容
     */
    private List<String> extractWordContent(XWPFDocument document) {
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        
        int paragraphCount = 0;
        int paragraphsPerPage = 15; // 每页15个段落
        
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            String text = paragraph.getText().trim();
            if (!text.isEmpty()) {
                currentPage.append(text).append("\n\n");
                paragraphCount++;
                
                if (paragraphCount >= paragraphsPerPage) {
                    pages.add(currentPage.toString());
                    currentPage = new StringBuilder();
                    paragraphCount = 0;
                }
            }
        }
        
        // 添加最后一页
        if (currentPage.length() > 0) {
            pages.add(currentPage.toString());
        }
        
        return pages;
    }
    
    /**
     * 创建文本图片
     */
    private BufferedImage createTextImage(String content, int pageNumber) {
        int width = 1200;
        int height = 1600;
        
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        // 设置抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // 背景
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, width, height);
        
        // 标题
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("宋体", Font.BOLD, 20));
        String title = "Word文档 - 第 " + pageNumber + " 页";
        g2d.drawString(title, 50, 80);
        
        // 内容
        g2d.setFont(new Font("宋体", Font.PLAIN, 16));
        String[] lines = content.split("\n");
        int y = 150;
        int lineHeight = 28;
        
        for (String line : lines) {
            if (y + lineHeight > height - 100) break;
            
            // 处理长行
            if (line.length() > 60) {
                int start = 0;
                while (start < line.length()) {
                    int end = Math.min(start + 60, line.length());
                    String subLine = line.substring(start, end);
                    g2d.drawString(subLine, 60, y);
                    y += lineHeight;
                    start = end;
                    if (y + lineHeight > height - 100) break;
                }
            } else {
                g2d.drawString(line, 60, y);
                y += lineHeight;
            }
        }
        
        g2d.dispose();
        return image;
    }
}
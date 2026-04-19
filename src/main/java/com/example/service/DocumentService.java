package com.example.service;

import com.example.dto.ConsumerComplaintRequestDTO;
import com.example.dto.DepositRefundRequestDTO;
import com.example.util.JsonResponse;

import java.io.IOException;

/**
 * 文档生成服务接口
 */
public interface DocumentService {

    /**
     * 生成要求返还押金函（返回字节数组）
     * @param requestDTO 请求参数
     * @return 文档字节数组
     * @throws IOException 文件操作异常
     */
    byte[] generateDepositRefundLetter(DepositRefundRequestDTO requestDTO) throws IOException;

    /**
     * 生成Base64编码的文档
     * @param requestDTO 请求参数
     * @return Base64编码的文档字符串
     * @throws IOException 文件操作异常
     */
    String generateBase64Document(DepositRefundRequestDTO requestDTO) throws IOException;

    /**
     * 获取生成的文件名
     * @param requestDTO 请求参数
     * @return 文件名
     */
    String getFileName(DepositRefundRequestDTO requestDTO);

    /**
     * 生成消费者协会投诉书
     */
    byte[] generateConsumerComplaintLetter(ConsumerComplaintRequestDTO requestDTO) throws IOException;

    /**
     * 获取消费者协会投诉书文件名
     */
    String getConsumerComplaintFileName(ConsumerComplaintRequestDTO requestDTO);
}
package com.example.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 客户端与 RAG 索引配置。
 *
 * <p>向量检索参数（索引名 / KNN 开关 / RRF 权重 / k / 切片大小）走 application.yml 的
 * {@code rag.*}，此处只负责把 {@code rag.es.index-name} 绑定为常量并暴露 ES 客户端 Bean。
 */
@Slf4j
@Configuration
public class RagEsConfig {

    /** RAG 知识库索引名 */
    public static final String DEFAULT_INDEX = "rag_knowledge_base";

    @Value("${rag.es.index-name:rag_knowledge_base}")
    private String indexName;

    @Value("${elasticsearch.host:localhost}")
    private String esHost;

    @Value("${elasticsearch.port:9200}")
    private int esPort;

    @Value("${elasticsearch.protocol:http}")
    private String esProtocol;

    @Value("${rag.chunk.size:1500}")
    private int chunkSize;

    @Value("${rag.chunk.overlap:200}")
    private int chunkOverlap;

    @Value("${rag.hybrid.knn.enabled:true}")
    private boolean knnEnabled;

    @Value("${rag.hybrid.rrf.weight.knn:1.0}")
    private double rrfWeightKnn;

    @Value("${rag.hybrid.rrf.weight.bm25:0.3}")
    private double rrfWeightBm25;

    @Value("${rag.hybrid.rrf.k:10}")
    private int rrfK;

    public String getIndexName() { return indexName; }
    public int getChunkSize() { return chunkSize; }
    public int getChunkOverlap() { return chunkOverlap; }
    public boolean isKnnEnabled() { return knnEnabled; }
    public double getRrfWeightKnn() { return rrfWeightKnn; }
    public double getRrfWeightBm25() { return rrfWeightBm25; }
    public int getRrfK() { return rrfK; }

    /**
     * ES 客户端：走低层 RestClient 传输，便于设置超时与连接池。
     */
    @Bean(destroyMethod = "close")
    public RestClient esRestClient() {
        String scheme = "https".equalsIgnoreCase(esProtocol) ? "https" : "http";
        RestClient client = RestClient.builder(new HttpHost(esHost, esPort, scheme))
                .setRequestConfigCallback(cfg -> cfg
                        .setConnectTimeout(5000)
                        .setSocketTimeout(30000))
                .build();
        log.info("ES RestClient 已创建: {}://{}:{}", scheme, esHost, esPort);
        return client;
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient esRestClient, ObjectMapper objectMapper) {
        return new ElasticsearchClient(new RestClientTransport(esRestClient, new JacksonJsonpMapper(objectMapper)));
    }
}

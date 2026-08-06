package com.zxcSpringAI.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationScript;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识问答核心配置
 *
 * 负责装配对话上下文、Elasticsearch 连接、向量存储、知识库导入与内容检索组件。
 * 目标索引及写入规则由 rag.elasticsearch.* 配置，当前对接 ES 9.4.4（无鉴权）。
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int EMBEDDING_DIM = 1536;

    // ======================================================================
    // 对话上下文：保留最近 10 条消息窗口，用于多轮问答历史引用
    // ======================================================================
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .build();
    }

    // ======================================================================
    // Elasticsearch 连接
    // - 低级别 RestClient：用于发送原生 JSON 请求（写入后校验、自定义 script_score 查询）
    // - ElasticsearchClient：用于启动时的版本、健康、索引结构读取
    // 鉴权根据 spring.elasticsearch.username/password 可选启用
    // ======================================================================
    @Bean(destroyMethod = "close")
    public RestClient restClient(SpringElasticsearchProperties esProps) {
        HttpHost host = new HttpHost(esProps.getHost(), esProps.getPort(), esProps.getScheme());
        RestClientBuilder builder = RestClient.builder(host)
                .setRequestConfigCallback(cb -> cb
                        .setConnectTimeout(esProps.getConnectTimeout())
                        .setSocketTimeout(esProps.getSocketTimeout()));

        String user = esProps.getUsername();
        String pwd = esProps.getPassword();
        if (user != null && !user.isBlank() && pwd != null && !pwd.isBlank()) {
            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, pwd));
            builder.setHttpClientConfigCallback(hcb -> hcb.setDefaultCredentialsProvider(cp));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "_transport")
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    // ======================================================================
    // 向量存储 + 知识库文档一次性导入
    // 1) 启动时输出 ES 版本、集群健康、索引状态
    // 2) 按需删除旧索引，保证环境干净
    // 3) 通过 ElasticsearchEmbeddingStore 写入接口创建索引并灌入文档向量
    // 4) 写入完成后调用原生接口校验文档数、mapping 和相似度查询链路
    // ======================================================================
    @Bean
    public EmbeddingStore myEmbeddingStore(
            RestClient restClient,
            ElasticsearchClient esClient,
            RagElasticsearchProperties ragEsProps,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {

        String indexName = ragEsProps.getIndexName();

        // 1) 启动时环境校验
        diagnoseElasticsearch(esClient, indexName);

        // 2) 清理旧索引
        if (ragEsProps.isDeleteOnStartup()) {
            try {
                if (esClient.indices().exists(r -> r.index(indexName)).value()) {
                    esClient.indices().delete(d -> d.index(indexName));
                    log.warn("[ES] 已删除旧索引: {}", indexName);
                }
            } catch (Exception ignored) {
            }
        }

        // 3) 装配向量存储：统一使用 script_score 查询，跨 ES 版本兼容性最好
        ElasticsearchEmbeddingStore embeddingStore = ElasticsearchEmbeddingStore.builder()
                .client(esClient)
                .indexName(indexName)
                .configuration(ElasticsearchConfigurationScript.builder().build())
                .build();

        // 4) 读取知识库目录并灌入向量
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("ragDatabase");
        int docCount = documents == null ? 0 : documents.size();
        log.warn("[ES] 加载到知识库文档数(raw): {}", docCount);
        if (docCount > 0) {
            String sample = documents.get(0).text();
            log.warn("[ES] 文档[0]预览(前200字): {}", sample.length() > 200 ? sample.substring(0, 200) + "..." : sample);
            try {
                int dim = embeddingModel.embed("维度自测").content().vector().length;
                log.warn("[ES] EmbeddingModel 输出向量维度: {}", dim);
            } catch (Exception ignored) {
            }
            log.warn("[ES] 开始向量化写入...");
            EmbeddingStoreIngestor.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .build()
                    .ingest(documents);
            log.warn("[ES] ingest 调用完成。");
        }

        // 5) 写入后校验
        writeAfterVerify(restClient, indexName);
        return embeddingStore;
    }

    // ======================================================================
    // 写入后校验：刷新索引、输出文档数 / 字段 mapping / 样例文档 / script_score 示例查询
    // 用于在启动日志中确认向量库真实状态，便于排查导入或字段异常
    // ======================================================================
    private static void writeAfterVerify(RestClient restClient, String indexName) {
        try {
            restClient.performRequest(new Request("POST", "/" + indexName + "/_refresh"));

            Response countResp = restClient.performRequest(new Request("GET", "/" + indexName + "/_count"));
            String countText = EntityUtils.toString(countResp.getEntity(), "UTF-8");
            long count = OBJECT_MAPPER.readTree(countText).path("count").asLong(-1);
            log.warn("================ [ES] 写入后校验 ================");
            log.warn("[ES] 文档总数: {}", count);

            Response mapResp = restClient.performRequest(new Request("GET", "/" + indexName + "/_mapping"));
            JsonNode props = OBJECT_MAPPER.readTree(EntityUtils.toString(mapResp.getEntity(), "UTF-8"))
                    .path(indexName).path("mappings").path("properties");
            log.warn("[ES] Mapping 字段:");
            props.fields().forEachRemaining(e -> {
                String type = e.getValue().path("type").asText();
                String dims = e.getValue().path("dims").asText("");
                log.warn("[ES]      - {} -> type={}, dims={}", e.getKey(), type, dims);
            });

            String searchText = EntityUtils.toString(
                    restClient.performRequest(new Request("GET", "/" + indexName + "/_search?size=1"))
                            .getEntity(), "UTF-8");
            JsonNode firstHit = OBJECT_MAPPER.readTree(searchText).path("hits").path("hits").get(0);
            if (firstHit != null) {
                JsonNode src = firstHit.path("_source");
                log.warn("[ES] 首条文档字段: {}", OBJECT_MAPPER.writeValueAsString(src.fieldNames()));
                String vecField = null;
                String txtField = null;
                var it = src.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    if (e.getValue().isArray()) {
                        vecField = e.getKey();
                        log.warn("[ES]      向量字段 = {}，长度 = {}", vecField, e.getValue().size());
                    } else if (e.getValue().isTextual()) {
                        if (txtField == null || e.getValue().asText().length() > src.path(txtField).asText().length()) {
                            txtField = e.getKey();
                        }
                    }
                }
                if (txtField != null) {
                    String t = src.path(txtField).asText();
                    log.warn("[ES]      文本字段 = {}，前100字 = {}", txtField,
                            t.length() > 100 ? t.substring(0, 100) + "..." : t);
                }
                if (vecField != null && count > 0) {
                    String vecJson = OBJECT_MAPPER.writeValueAsString(src.path(vecField));
                    String queryText = "{"
                            + "\"size\":2,"
                            + "\"_source\":[\"" + (txtField == null ? "text" : txtField) + "\"],"
                            + "\"query\":{"
                            + "  \"script_score\":{"
                            + "    \"query\":{\"match_all\":{}},"
                            + "    \"script\":{"
                            + "      \"source\":\"cosineSimilarity(params.q, '" + vecField + "') + 1.0\","
                            + "      \"params\":{\"q\":" + vecJson + "}"
                            + "    }"
                            + "  }"
                            + "}}";
                    Request req = new Request("POST", "/" + indexName + "/_search");
                    req.setEntity(new StringEntity(queryText, ContentType.APPLICATION_JSON));
                    Response resp = restClient.performRequest(req);
                    String body = EntityUtils.toString(resp.getEntity(), "UTF-8");
                    if (resp.getStatusLine().getStatusCode() == 200) {
                        long total = OBJECT_MAPPER.readTree(body).path("hits").path("total").path("value").asLong();
                        log.warn("[ES] script_score 示例查询通过，命中 = {}", total);
                    } else {
                        log.warn("[ES] script_score 示例查询失败，status={}, body={}", resp.getStatusLine().getStatusCode(), body);
                    }
                }
            } else {
                log.warn("[ES] 未查询到首条文档，原始返回: {}", searchText);
            }
            log.warn("================ [ES] 写入后校验结束 ================");
        } catch (Exception e) {
            log.error("[ES] 写入后校验异常: {}", e.getMessage(), e);
        }
    }

    // ======================================================================
    // 内容检索：基于用户问题的向量做相似度查询
    // 流程：问题文本 -> 向量化 -> ES script_score cosineSimilarity -> 过滤分数 -> 组装片段
    // 该实现使用原生 JSON 请求，字段名固定为 text/vector，与向量库写入结构对齐
    // ======================================================================
    @Bean
    public ContentRetriever myContentRetriever(
            RestClient restClient,
            RagElasticsearchProperties ragEsProps,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {
        return new NativeScriptScoreContentRetriever(
                restClient,
                ragEsProps.getIndexName(),
                embeddingModel,
                3,
                0.2
        );
    }

    /**
     * 自定义向量内容检索：把用户问题转成向量后，在 ES 中以余弦相似度为分数进行检索。
     * 与 LangChain4j 内置实现相比，此处直接发送原生 JSON，便于控制查询参数与日志输出。
     */
    private static class NativeScriptScoreContentRetriever implements ContentRetriever {
        private static final Logger LOG = LoggerFactory.getLogger(NativeScriptScoreContentRetriever.class);
        private final RestClient restClient;
        private final String indexName;
        private final EmbeddingModel embeddingModel;
        private final int maxResults;
        private final double minScoreRaw;

        NativeScriptScoreContentRetriever(RestClient c, String idx, EmbeddingModel em, int mr, double ms) {
            this.restClient = c;
            this.indexName = idx;
            this.embeddingModel = em;
            this.maxResults = mr;
            this.minScoreRaw = ms + 1.0;
        }

        @Override
        public List<Content> retrieve(Query query) {
            float[] qv = embeddingModel.embed(query.text()).content().vector();
            String qVec;
            try {
                qVec = OBJECT_MAPPER.writeValueAsString(qv);
            } catch (Exception e) {
                throw new RuntimeException("问题文本向量化失败: " + e.getMessage(), e);
            }
            String body = "{"
                    + "\"size\":" + maxResults + ","
                    + "\"_source\":[\"text\",\"metadata\"],"
                    + "\"min_score\":" + minScoreRaw + ","
                    + "\"query\":{"
                    + "  \"script_score\":{"
                    + "    \"query\":{\"match_all\":{}},"
                    + "    \"script\":{"
                    + "      \"source\":\"cosineSimilarity(params.query_vector, 'vector') + 1.0\","
                    + "      \"params\":{\"query_vector\":" + qVec + "}"
                    + "    }"
                    + "  }"
                    + "}}";
            Request req = new Request("POST", "/" + indexName + "/_search");
            req.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            try {
                Response resp = restClient.performRequest(req);
                String respText = EntityUtils.toString(resp.getEntity(), "UTF-8");
                int status = resp.getStatusLine().getStatusCode();
                if (status != 200) {
                    LOG.error("[ES检索] 查询失败 status={}, body={}", status, respText);
                    throw new RuntimeException("ES 检索失败 HTTP " + status + ": " + respText);
                }
                JsonNode root = OBJECT_MAPPER.readTree(respText);
                JsonNode hits = root.path("hits").path("hits");
                List<Content> out = new ArrayList<>();
                if (hits.isArray()) {
                    for (JsonNode h : hits) {
                        double score = h.path("_score").asDouble(0.0);
                        String text = h.path("_source").path("text").asText("");
                        if (!text.isBlank()) {
                            out.add(Content.from(TextSegment.from(text)));
                            LOG.info("[ES检索] 命中 score={}，片段前120字={}",
                                    score, text.length() > 120 ? text.substring(0, 120) + "..." : text);
                        }
                    }
                }
                LOG.info("[ES检索] 查询完成，命中{}条，问题前50字={}", out.size(),
                        query.text().length() > 50 ? query.text().substring(0, 50) + "..." : query.text());
                return out;
            } catch (Exception e) {
                LOG.error("[ES检索] 查询异常: {}", e.getMessage(), e);
                throw new RuntimeException("ES 检索异常: " + e.getMessage(), e);
            }
        }
    }

    // ======================================================================
    // 启动时诊断：ES 版本、集群健康、索引存在性及字段结构，用于启动日志快速查看环境状态
    // ======================================================================
    private static void diagnoseElasticsearch(ElasticsearchClient esClient, String indexName) {
        try {
            var info = esClient.info();
            String version = info.version().number();
            String clusterName = info.clusterName();
            log.warn("=================== ES 诊断开始 ===================");
            log.warn("[ES] 集群: {}  |  服务器版本: {}", clusterName, version);

            HealthResponse h = esClient.cluster().health();
            log.warn("[ES] 集群健康: status={}, 节点数={}, 活跃分片={}",
                    h.status(), h.numberOfNodes(), h.activeShards());

            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            log.warn("[ES] 索引[{}] 是否存在: {}", indexName, exists);
            if (exists) {
                try {
                    SearchResponse<Map> s = esClient.search(q -> q.index(indexName).size(0), Map.class);
                    log.warn("[ES] 索引文档数: {}", s.hits().total().value());
                } catch (Exception ignored) {
                }
                try {
                    IndexState state = esClient.indices().get(g -> g.index(indexName)).indices().get(indexName);
                    if (state != null && state.mappings() != null && state.mappings().properties() != null) {
                        Map<String, Property> properties = state.mappings().properties();
                        log.warn("[ES] Mapping 字段:");
                        properties.forEach((name, prop) -> {
                            String extra = "";
                            if (prop.isDenseVector()) {
                                extra = " (dims=" + prop.denseVector().dims() + ")";
                            } else if (prop.isObject()) {
                                extra = " (enabled=" + (prop.object().enabled() != null && Boolean.TRUE.equals(prop.object().enabled())) + ")";
                            }
                            log.warn("[ES]      - {} -> {}{}", name, propertyTypeName(prop), extra);
                        });
                    }
                } catch (Exception ignored) {
                }
            }
            log.warn("=================== ES 诊断结束 ===================");
        } catch (Exception e) {
            log.error("[ES] 诊断失败: {}", e.getMessage(), e);
        }
    }

    // ======================================================================
    // 辅助：Property -> 可读字段类型名
    // ======================================================================
    private static String propertyTypeName(Property p) {
        if (p == null) return "null";
        if (p.isText()) return "text";
        if (p.isKeyword()) return "keyword";
        if (p.isDenseVector()) return "dense_vector";
        if (p.isObject()) return "object";
        if (p.isNested()) return "nested";
        if (p.isInteger() || p.isLong()) return "long";
        if (p.isFloat()) return "float";
        if (p.isDouble()) return "double";
        if (p.isBoolean()) return "boolean";
        if (p.isIp()) return "ip";
        if (p.isDate()) return "date";
        if (p.isGeoPoint()) return "geo_point";
        return p._kind().name().toLowerCase();
    }

    // 保留常量，便于后续扩展自定义索引创建逻辑
    @SuppressWarnings("unused")
    private static int embeddingDim() {
        return EMBEDDING_DIM;
    }
}

package com.zxcSpringAI.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.json.JsonData;
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
import java.util.Set;

/**
 * AI / RAG 核心配置
 * - ES 服务器版本：9.4.4（无鉴权）
 * - 向量维度：1536（对齐 DashScope text-embedding-v2）
 * - 向量索引策略：ES 9.x 默认 HNSW kNN，使用 ElasticsearchConfigurationKnn 查询
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int EMBEDDING_DIM = 1536;

    // ======================================================================
    // 1. 对话记忆（10条消息窗口）
    // ======================================================================
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .build();
    }

    // ======================================================================
    // 2. ES 客户端（RestClient + 官方 ElasticsearchClient）
    // ======================================================================
    @Bean(destroyMethod = "close")
    public RestClient restClient(SpringElasticsearchProperties esProps) {
        HttpHost host = new HttpHost(esProps.getHost(), esProps.getPort(), esProps.getScheme());
        RestClientBuilder builder = RestClient.builder(host)
                .setRequestConfigCallback(cb -> cb
                        .setConnectTimeout(esProps.getConnectTimeout())
                        .setSocketTimeout(esProps.getSocketTimeout()));

        // 用户名密码都配置了才启用 Basic Auth（当前服务器 9.4.4 无鉴权）
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
    // 3. 向量存储 EmbeddingStore（ES 9.4.4 dense_vector + HNSW kNN）
    // ======================================================================
    @Bean
    public EmbeddingStore myEmbeddingStore(
            RestClient restClient,
            ElasticsearchClient esClient,
            RagElasticsearchProperties ragEsProps,
            @Qualifier("openAiEmbeddingModel") EmbeddingModel embeddingModel) {

        String indexName = ragEsProps.getIndexName();

        // --- 1. 启动时快速诊断 ---
        diagnoseElasticsearch(esClient, indexName);

        // --- 2. 启动时删除旧索引（可选，默认开启），保证干净环境 ---
        if (ragEsProps.isDeleteOnStartup()) {
            try {
                if (esClient.indices().exists(r -> r.index(indexName)).value()) {
                    esClient.indices().delete(d -> d.index(indexName));
                    log.warn("[ES] 已删除旧索引: {}", indexName);
                }
            } catch (Exception ignored) {
            }
        }

        // --- 3. 【不手动建索引】：完全由 langchain4j 的 ElasticsearchEmbeddingStore
        //    在第一次写入时自动创建完全匹配自己写入格式的 mapping（字段名/向量类型/维度100%对齐）。
        //    不提前声明，避免我们手动指定的 text/vector/metadata 与 langchain4j 真实写入不一致。

        // --- 4. 构建 EmbeddingStore：根据已确认的 mapping（首启诊断已实锤字段名 vector/text/metadata，
        //    dense_vector(dims=1536)），显式使用 script_score 查询（对 ES 版本要求最低，和你 curl
        //    调通的 cosineSimilarity script 语法完全一致）。默认 Knn 查询在部分版本存在 num_candidates
        //    与实际 sharding 的兼容问题会导致 all shards failed，此处强制走最稳妥的链路。
        ElasticsearchEmbeddingStore embeddingStore = ElasticsearchEmbeddingStore.builder()
                .client(esClient)
                .indexName(indexName)
                .configuration(ElasticsearchConfigurationScript.builder().build())
                .build();

        // --- 5. 加载知识库文档（打印详细预览，验证 ClassPathDocumentLoader 是否真读到了内容）---
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("ragDatabase");
        int docN = documents == null ? 0 : documents.size();
        log.warn("[ES] 加载到知识库文档数(raw): {}", docN);
        if (docN > 0) {
            String t = documents.get(0).text();
            log.warn("[ES] 文档[0]预览(前200字): {}", t.length() > 200 ? t.substring(0, 200) + "..." : t);
            // 也打印一下 embedding 模型实际输出维度
            try {
                float[] firstVec = embeddingModel.embed("维度自测").content().vector();
                log.warn("[ES] EmbeddingModel 输出向量维度: {}", firstVec.length);
            } catch (Exception ignored) {
            }
        }

        // --- 6. 向量化 + 写入 ES（这里会触发 langchain4j 自动创建它需要的 mapping）---
        if (docN > 0) {
            log.warn("[ES] 开始向量化写入...");
            EmbeddingStoreIngestor.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .build()
                    .ingest(documents);
            log.warn("[ES] ingest 调用完成。");
        }

        // --- 7. 写入后：用原生 REST 完整诊断真实状态，不再做任何猜测 ---
        writeAfterNativeDiagnose(restClient, indexName);

        return embeddingStore;
    }

    // ======================================================================
    // 写入后完整原生诊断（_mapping + _count + _search 结构 + script_score 自测）
    // ======================================================================
    private static void writeAfterNativeDiagnose(RestClient restClient, String indexName) {
        try {
            // 7.1 强制 refresh（ES 近实时刷新默认 1 秒，这里手动催一下）
            restClient.performRequest(new Request("POST", "/" + indexName + "/_refresh"));

            // 7.2 _count
            Response countResp = restClient.performRequest(new Request("GET", "/" + indexName + "/_count"));
            String countText = EntityUtils.toString(countResp.getEntity(), "UTF-8");
            long count = OBJECT_MAPPER.readTree(countText).path("count").asLong(-1);
            log.warn("================ [ES] 写入后完整诊断 ================");
            log.warn("[ES 7.2] _count = {}", count);

            // 7.3 _mapping（关键！看 langchain4j 真实创建的字段名/向量维度）
            Response mapResp = restClient.performRequest(new Request("GET", "/" + indexName + "/_mapping"));
            String mapJson = EntityUtils.toString(mapResp.getEntity(), "UTF-8");
            JsonNode rootMap = OBJECT_MAPPER.readTree(mapJson);
            JsonNode props = rootMap.path(indexName).path("mappings").path("properties");
            log.warn("[ES 7.3] 真实 mapping 字段：");
            props.fields().forEachRemaining(e -> {
                String name = e.getKey();
                JsonNode meta = e.getValue();
                String type = meta.path("type").asText();
                String dims = meta.path("dims").asText("");
                log.warn("[ES 7.3]      - {} -> type={}, dims={}", name, type, dims);
            });

            // 7.4 取首条文档，看真实 _source 里有哪些字段（至关重要：text/vector 还是 content/embedding？）
            String search1 = EntityUtils.toString(
                    restClient.performRequest(new Request("GET", "/" + indexName + "/_search?size=1"))
                            .getEntity(), "UTF-8");
            JsonNode searchNode = OBJECT_MAPPER.readTree(search1);
            JsonNode firstHit = searchNode.path("hits").path("hits").get(0);
            if (firstHit != null) {
                JsonNode src = firstHit.path("_source");
                log.warn("[ES 7.4] 首条文档 _source 字段名: {}", OBJECT_MAPPER.writeValueAsString(src.fieldNames()));
                // 找出向量字段 + 文本字段
                String vecField = null;
                String txtField = null;
                var it = src.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    if (e.getValue().isArray()) {
                        vecField = e.getKey();
                        log.warn("[ES 7.4]      向量字段 = {}，长度 = {}", vecField, e.getValue().size());
                    } else if (e.getValue().isTextual()) {
                        if (txtField == null || e.getValue().asText().length() > (src.path(txtField).asText().length())) {
                            txtField = e.getKey();
                        }
                    }
                }
                if (txtField != null) {
                    String t = src.path(txtField).asText();
                    log.warn("[ES 7.4]      文本字段 = {}，前100字 = {}", txtField,
                            t.length() > 100 ? t.substring(0, 100) + "..." : t);
                }
                // 7.5 用真实字段名发一条原生 script_score 自测（curl 成功你肯定能通过这个）
                if (vecField != null && count > 0) {
                    String vecJson = OBJECT_MAPPER.writeValueAsString(src.path(vecField));
                    String q = "{"
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
                    req.setEntity(new StringEntity(q, ContentType.APPLICATION_JSON));
                    Response resp = restClient.performRequest(req);
                    String rbody = EntityUtils.toString(resp.getEntity(), "UTF-8");
                    if (resp.getStatusLine().getStatusCode() == 200) {
                        long total = OBJECT_MAPPER.readTree(rbody).path("hits").path("total").path("value").asLong();
                        log.warn("[ES 7.5] script_score 自测 ✅  status=200, hits={}", total);
                    } else {
                        log.warn("[ES 7.5] script_score 自测 ❌  status={}, body={}", resp.getStatusLine().getStatusCode(), rbody);
                    }
                } else {
                    log.warn("[ES 7.5] 跳过自测：count={}, 向量字段存在? {}", count, vecField != null);
                }
            } else {
                log.warn("[ES 7.4] 没有首条文档，_search 原始返回: {}", search1);
            }
            log.warn("================ [ES] 写入后诊断结束 ================");
        } catch (Exception e) {
            log.error("[ES 写入后诊断] 异常: {}", e.getMessage(), e);
        }
    }

    // ======================================================================
    // 4. 内容检索器（手动实现：直接用 RestClient 发原生 script_score 查询，
    //    和你 curl 调通的完全等价；不再依赖 langchain4j 的 EmbeddingStore.search()
    //    对 Knn/Script 配置的不透明实现，彻底根治 all shards failed
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
                3,      // maxResults（调大，先召回更多再让模型筛选）
                0.2     // minScore：阈值先放开（相当于 cos≥-0.8），保证哪怕弱相关也能召回；
                        // 后续等你实测知识库内容的相似度分布再逐步收紧
        );
    }

    /**
     * 原生 script_score 检索实现（完全等价 curl 命中逻辑：
     * 1) 用 EmbeddingModel 把问题文本 -> 1536 维向量
     * 2) POST /{index}/_search  { script_score: { cosineSimilarity + 1.0 }}
     * 3) 过滤分数，按阈值裁剪 -> 组装 Content 列表返回
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
            this.minScoreRaw = ms + 1.0; // cosineSimilarity 返回 +1.0（避免负数）
        }

        @Override
        public List<Content> retrieve(Query query) {
            // a) 先对用户问题向量化
            float[] qv = embeddingModel.embed(query.text()).content().vector();
            String qVec;
            try {
                qVec = OBJECT_MAPPER.writeValueAsString(qv);
            } catch (Exception e) {
                throw new RuntimeException("向量化失败: " + e.getMessage(), e);
            }
            // b) 构造与 curl 等价的 JSON script_score 查询
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
            // c) 执行并解析
            try {
                Response resp = restClient.performRequest(req);
                String respText = EntityUtils.toString(resp.getEntity(), "UTF-8");
                int status = resp.getStatusLine().getStatusCode();
                if (status != 200) {
                    LOG.error("[RAG-Native] script_score 查询失败 status={}, body={}", status, respText);
                    throw new RuntimeException("ES script_score 查询失败 HTTP " + status + ": " + respText);
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
                            LOG.info("[RAG-Native] 命中，score(+1后)={}，片段前120字={}",
                                    score, text.length() > 120 ? text.substring(0, 120) + "..." : text);
                        }
                    }
                }
                LOG.info("[RAG-Native] 查询完成，命中{}条，问题前50字={}", out.size(),
                        query.text().length() > 50 ? query.text().substring(0, 50) + "..." : query.text());
                return out;
            } catch (Exception e) {
                LOG.error("[RAG-Native] 查询异常: {}", e.getMessage(), e);
                throw new RuntimeException("script_score 检索异常: " + e.getMessage(), e);
            }
        }
    }

    // ======================================================================
    // 内部辅助：创建 rag 索引（ES 9.x 原生 DSL，避免客户端强类型 mapping 隐式假设）
    // ======================================================================
    private static void createRagIndexEs9Native(RestClient restClient, String indexName, int dims) {
        try {
            boolean exists;
            try {
                Response head = restClient.performRequest(new Request("HEAD", "/" + indexName));
                exists = head.getStatusLine().getStatusCode() == 200;
            } catch (Exception e) {
                exists = false;
            }
            if (exists) {
                log.warn("[ES] 索引[{}]已存在，跳过创建。如需重建请设置 rag.elasticsearch.delete-on-startup=true", indexName);
                return;
            }

            // ES 9.4.4 dense_vector 默认启用 HNSW 索引。
            // 为避免 langchain4j 对 knn/script_score 的 mapping 假设不匹配，
            // 这里使用最兼容的声明方式：仅显式指定 dims + type
            String body = "{\n"
                    + "  \"mappings\": {\n"
                    + "    \"properties\": {\n"
                    + "      \"text\":     { \"type\": \"text\" },\n"
                    + "      \"vector\":   { \"type\": \"dense_vector\", \"dims\": " + dims + " },\n"
                    + "      \"metadata\": { \"type\": \"object\", \"enabled\": false }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}";

            Request put = new Request("PUT", "/" + indexName);
            put.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
            Response resp = restClient.performRequest(put);
            String respText = EntityUtils.toString(resp.getEntity(), "UTF-8");
            log.warn("[ES] 创建索引 DSL 响应: {}", respText);

            // 校验 mapping（原生 JSON 解析，避免 Property 强类型差异）
            String mappingJson = EntityUtils.toString(
                    restClient.performRequest(new Request("GET", "/" + indexName + "/_mapping")).getEntity(), "UTF-8");
            JsonNode mapNode = OBJECT_MAPPER.readTree(mappingJson);
            JsonNode vectorMeta = mapNode.path(indexName).path("mappings").path("properties").path("vector");
            String type = vectorMeta.path("type").asText();
            String d = vectorMeta.path("dims").asText("");
            log.warn("[ES] 创建后校验: vector.type={}, vector.dims={}", type, d);
            if (!"dense_vector".equals(type)) {
                throw new IllegalStateException("vector 字段类型为 '" + type + "'（应该是 dense_vector）。当前 mapping: " + mappingJson);
            }
            log.warn("[ES] 索引[{}] 创建成功 ✅", indexName);
        } catch (Exception e) {
            log.error("[ES] 创建索引失败: {}", e.getMessage(), e);
            throw new RuntimeException("创建 rag_embeddings 索引失败: " + e.getMessage(), e);
        }
    }

    // ======================================================================
    // 内部辅助：脚本相似度查询自测（启动时验证检索链路能真正跑通）
    // ======================================================================
    private static void rawScriptScoreSelfTest(RestClient restClient, String indexName) {
        try {
            // 1) 取第一个文档作为查询向量源
            Response docResp = restClient.performRequest(new Request("GET", "/" + indexName + "/_search?size=1"));
            JsonNode docNode = OBJECT_MAPPER.readTree(EntityUtils.toString(docResp.getEntity(), "UTF-8"));
            JsonNode hits = docNode.path("hits").path("hits");
            if (!hits.isArray() || hits.size() == 0) {
                log.warn("[ES 自测] 索引无文档，跳过 script_score 自测");
                return;
            }
            JsonNode vectorNode = hits.get(0).path("_source").path("vector");
            if (vectorNode.isMissingNode() || !vectorNode.isArray()) {
                log.warn("[ES 自测] 文档无 vector 数组，跳过自测。hits[0]: {}", hits.get(0));
                return;
            }
            String vectorStr = OBJECT_MAPPER.writeValueAsString(vectorNode);

            // 2) 构造 script_score 查询（ES 7/8/9 都原生支持 cosineSimilarity）
            String query = "{"
                    + "\"size\":2,"
                    + "\"_source\":[\"text\"],"
                    + "\"query\":{"
                    + "  \"script_score\":{"
                    + "    \"query\":{\"match_all\":{}},"
                    + "    \"script\":{"
                    + "      \"source\":\"cosineSimilarity(params.query_vector, 'vector') + 1.0\","
                    + "      \"params\":{\"query_vector\":" + vectorStr + "}"
                    + "    }"
                    + "  }"
                    + "}}";
            Request req = new Request("POST", "/" + indexName + "/_search");
            req.setEntity(new StringEntity(query, ContentType.APPLICATION_JSON));
            Response resp = restClient.performRequest(req);
            int status = resp.getStatusLine().getStatusCode();
            String body = EntityUtils.toString(resp.getEntity(), "UTF-8");
            if (status == 200) {
                JsonNode respNode = OBJECT_MAPPER.readTree(body);
                long total = respNode.path("hits").path("total").path("value").asLong();
                log.warn("[ES 自测] script_score 成功 ✅  status=200, hits_total={}", total);
            } else {
                log.warn("[ES 自测] script_score 失败 status={}, body={}", status, body);
            }
        } catch (Exception e) {
            log.error("[ES 自测] script_score 异常: {}", e.getMessage(), e);
        }
    }

    // ======================================================================
    // 内部辅助：Property -> 字段类型名
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

    // ======================================================================
    // 内部辅助：启动时诊断
    // ======================================================================
    private static void diagnoseElasticsearch(ElasticsearchClient esClient, String indexName) {
        try {
            // ES 版本与集群名
            var info = esClient.info();
            String version = info.version().number();
            String clusterName = info.clusterName();
            log.warn("=================== ES 诊断开始 ===================");
            log.warn("[ES] 集群: {}  |  服务器版本: {}", clusterName, version);

            // 集群健康
            HealthResponse h = esClient.cluster().health();
            log.warn("[ES] 集群健康: status={}, 节点数={}, 活跃分片={}",
                    h.status(), h.numberOfNodes(), h.activeShards());

            // 索引存在性 + mapping
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
                        log.warn("[ES] Mapping字段:");
                        properties.forEach((name, prop) -> {
                            String extra = "";
                            if (prop.isDenseVector()) {
                                extra = " (dims=" + prop.denseVector().dims() + ")";
                            } else if (prop.isObject()) {
                                extra = " (enabled=" + (prop.object().enabled() != null && prop.object().enabled()) + ")";
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

}

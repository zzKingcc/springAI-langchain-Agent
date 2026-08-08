package com.zxcSpringAI.util;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 向量索引操作工具
 *
 * 封装与 Elasticsearch 向量索引相关的辅助操作，全部通过 ElasticsearchClient 高级 API 实现：
 * 1) 启动环境诊断：集群版本、健康状态、索引存在性与字段 mapping；
 * 2) 旧索引删除：根据配置项决定启动时是否清理索引；
 * 3) 写入后校验：刷新索引、文档计数、字段 mapping 展示、script_score 示例查询；
 * 4) mapping 解析：将 ES Property 类型转为可读名称。
 */
public class VectorStoreUtil {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreUtil.class);

    private VectorStoreUtil() {
    }

    /**
     * 启动诊断：输出 ES 版本、集群健康、索引存在性及字段结构
     */
    public static void diagnoseElasticsearch(ElasticsearchClient esClient, String indexName) {
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
                    log.warn("[ES] 索引文档数: {}", s.hits().total() != null ? s.hits().total().value() : "N/A");
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

    /**
     * 按需删除旧索引：当 rag.elasticsearch.delete-on-startup = true 时执行
     */
    public static void deleteIndexIfNeeded(ElasticsearchClient esClient, String indexName, boolean deleteOnStartup) {
        if (!deleteOnStartup) return;
        try {
            if (esClient.indices().exists(r -> r.index(indexName)).value()) {
                esClient.indices().delete(d -> d.index(indexName));
                log.warn("[ES] 已删除旧索引: {}", indexName);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 写入后校验：刷新索引，输出文档数 / 字段 mapping / 样例文档 / script_score 示例查询结果
     */
    public static void writeAfterVerify(ElasticsearchClient esClient, String indexName) {
        try {
            esClient.indices().refresh(r -> r.index(indexName));

            long count = esClient.count(c -> c.index(indexName)).count();
            log.warn("================ [ES] 写入后校验 ================");
            log.warn("[ES] 文档总数: {}", count);

            try {
                IndexState state = esClient.indices().get(g -> g.index(indexName)).indices().get(indexName);
                if (state != null && state.mappings() != null && state.mappings().properties() != null) {
                    Map<String, Property> properties = state.mappings().properties();
                    log.warn("[ES] Mapping 字段:");
                    properties.forEach((name, prop) -> {
                        String type = propertyTypeName(prop);
                        String dims = prop.isDenseVector() ? String.valueOf(prop.denseVector().dims()) : "";
                        log.warn("[ES]      - {} -> type={}, dims={}", name, type, dims);
                    });
                }
            } catch (Exception ignored) {
            }

            SearchResponse<Map> searchResp = esClient.search(s -> s.index(indexName).size(1), Map.class);
            Map<String, Object> firstSource = null;
            if (searchResp.hits().hits() != null && !searchResp.hits().hits().isEmpty()) {
                firstSource = searchResp.hits().hits().get(0).source();
            }

            if (firstSource != null) {
                log.warn("[ES] 首条文档字段: {}", firstSource.keySet());
                String vecField = null;
                String txtField = null;
                int vecLen = 0;
                Object[] vecArr = null;

                for (Map.Entry<String, Object> e : firstSource.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    if (v.getClass().isArray()) {
                        vecField = e.getKey();
                        if (v instanceof double[]) {
                            vecLen = ((double[]) v).length;
                            vecArr = toObjectArray((double[]) v);
                        } else if (v instanceof float[]) {
                            vecLen = ((float[]) v).length;
                            vecArr = toObjectArray((float[]) v);
                        } else if (v instanceof int[]) {
                            vecLen = ((int[]) v).length;
                        }
                        log.warn("[ES]      向量字段 = {}，长度 = {}", vecField, vecLen);
                    } else if (v instanceof java.util.List) {
                        vecField = e.getKey();
                        vecLen = ((java.util.List<?>) v).size();
                        vecArr = ((java.util.List<?>) v).toArray();
                        log.warn("[ES]      向量字段 = {}，长度 = {}", vecField, vecLen);
                    } else if (v instanceof String) {
                        String sv = (String) v;
                        String curTxt = (String) firstSource.get(txtField);
                        if (txtField == null || (curTxt != null && sv.length() > curTxt.length())) {
                            txtField = e.getKey();
                        }
                    }
                }

                if (txtField != null) {
                    String t = (String) firstSource.get(txtField);
                    if (t != null) {
                        log.warn("[ES]      文本字段 = {}，前100字 = {}", txtField,
                                t.length() > 100 ? t.substring(0, 100) + "..." : t);
                    }
                }

                if (vecField != null && vecArr != null && count > 0) {
                    try {
                        String finalVecField = vecField;
                        String finalTxtField = txtField == null ? "text" : txtField;

                        Map<String, JsonData> params = new HashMap<>();
                        params.put("q", JsonData.of(vecArr));

                        co.elastic.clients.elasticsearch._types.Script script =
                                new co.elastic.clients.elasticsearch._types.Script.Builder()
                                        .source(ss -> ss.scriptString(
                                                "cosineSimilarity(params.q, '" + finalVecField + "') + 1.0"))
                                        .params(params)
                                        .build();

                        co.elastic.clients.elasticsearch._types.query_dsl.Query scriptScoreQuery =
                                new co.elastic.clients.elasticsearch._types.query_dsl.Query.Builder()
                                        .scriptScore(ss -> ss
                                                .query(q -> q.matchAll(m -> m))
                                                .script(script))
                                        .build();

                        SearchResponse<Map> exampleResp = esClient.search(s -> s
                                        .index(indexName)
                                        .size(2)
                                        .source(src -> src.filter(f -> f.includes(finalTxtField)))
                                        .query(scriptScoreQuery),
                                Map.class);
                        long total = exampleResp.hits().total() != null ? exampleResp.hits().total().value() : 0;
                        log.warn("[ES] script_score 示例查询通过，命中 = {}", total);
                    } catch (Exception e) {
                        log.warn("[ES] script_score 示例查询失败: {}", e.getMessage());
                    }
                }
            } else {
                log.warn("[ES] 未查询到首条文档，size(0) total = {}",
                        searchResp.hits().total() != null ? searchResp.hits().total().value() : 0);
            }
            log.warn("================ [ES] 写入后校验结束 ================");
        } catch (Exception e) {
            log.error("[ES] 写入后校验异常: {}", e.getMessage(), e);
        }
    }

    private static Object[] toObjectArray(double[] arr) {
        Object[] out = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i];
        return out;
    }

    private static Object[] toObjectArray(float[] arr) {
        Object[] out = new Object[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = (double) arr[i];
        return out;
    }

    /**
     * 将 ES Property 转为可读的字段类型名称
     */
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
}

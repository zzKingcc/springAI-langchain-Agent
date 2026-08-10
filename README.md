# springAI-Langchain-Agent

基于 Spring Boot 3.5 + LangChain4j 1.18.1 构建的 RAG 智能客服系统。以 Agent 编排为核心，让大模型自主决定何时检索知识库、何时调用工具，一套对话入口同时覆盖业务问答、天气查询与日常闲聊。

## 功能亮点

### Agent 自主编排

不写死任何对话流程。大模型读取工具描述后，自己判断当前问题该不该检索、该调哪个工具、调用顺序如何。问"公司有什么产品"会触发知识库检索，问"今天要带伞吗"会调用天气工具，纯问候则直接作答——全程没有 if-else 分支，新增工具只需加一个 `@Tool` 方法。

### 混合检索 + 分数融合

单一检索方式总有盲区：向量检索懂语义但抓不准型号编号，关键词检索抓得准却不懂同义表达。系统并行跑两路检索——向量取 Top15、关键词取 Top5，对分数做 min-max 归一化后按 0.6 / 0.4 加权融合，再对标题命中、文件名命中加 boost 重排序，兼顾召回率和精确率。

### 双约束会话记忆

只限条数会被长回答撑爆 Token 窗口，只限 Token 又会留太多短消息。`DualConstraintChatMemory` 同时卡住消息条数（100 条）和 Token 估算（30K），哪个先触顶就裁剪哪个，裁剪时保留系统提示和最近几轮对话。记忆落 Redis，按 `sessionId` 隔离，服务重启会话不丢。

### 流式输出

模型边生成边往外吐，不用等整段写完才返回。`StreamingChatModel` + `TokenStream` 把首字延迟压到百毫秒级，体验上更接近真人客服逐字回复，而不是盯着空白转圈。

### 输入安全防护

在请求进入模型之前，`InputSanitizer` 用五维正则先扫一遍用户输入，覆盖指令覆盖、角色混淆、分隔符注入、提示词窃取、编码绕过五类提示词注入攻击，命中即直接拦掉，不放进模型层。

### 文档智能导入

按文件类型走不同处理策略，中文文档能识别章节边界做分片，再过两层去重去掉重复片段，最后按 25 条一批送 embedding 接口向量化，符合模型批量限制。扔进去的 txt、md、pdf 都能自动消化进知识库。

## 技术栈

| 层次 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 3.5.7 |
| AI 框架 | LangChain4j | 1.18.1 |
| LLM | 通义千问 qwen3.7-plus（阿里百炼） | - |
| Embedding | text-embedding-v2（1536 维） | - |
| 向量存储 | Elasticsearch | 9.4.4 |
| 会话记忆 | Redis | - |
| JDK | OpenJDK | 21 |

## 架构

```
用户请求 (GET /test/agent/{sessionId}/{message})
    │
    ▼
TestController ─── InputSanitizer（输入安全检测）
    │
    ▼
AgentOrchestrationService（LangChain4j AiServices）
    │  ┌─────────────────────────────────────────┐
    │  │ 注入：StreamingChatModel / 记忆 / 工具 / 提示词 │
    │  └─────────────────────────────────────────┘
    │
    ├── 模型自主决策是否调用 @Tool
    │   ├── searchKnowledgeBase → 向量+关键词混合检索 → 融合重排
    │   └── queryWeather / queryMaxTemperature / ...（天气工具）
    │
    ├── 会话记忆读写（按 sessionId 隔离）
    │
    ▼
TokenStream 流式输出 → Flux<String> → HTTP 响应
```

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- Elasticsearch 9.4.4（需安装 IK 分词器插件）
- Redis 6+
- 阿里百炼 API Key（开通 qwen3.7-plus 和 text-embedding-v2 模型权限）

### 安装步骤

1. 克隆仓库

```bash
git clone https://gitee.com/your-username/springAI-Langchain-Agent.git
cd springAI-Langchain-Agent
```

2. 配置 `src/main/resources/application.yaml`，填写必要参数：

```yaml
langchain4j:
  open-ai:
    streaming-chat-model:
      base-url: "https://dashscope.aliyuncs.com/compatible-mode/v1"
      api-key: your-api-key
      model-name: "qwen3.7-plus"
    embedding-model:
      base-url: "https://dashscope.aliyuncs.com/compatible-mode/v1"
      api-key: your-api-key
      model-name: "text-embedding-v2"

spring:
  elasticsearch:
    host: your-es-host
    port: 9200
  data:
    redis:
      host: your-redis-host
      port: 6379
      password: your-password
```

3. 把知识库文档放进 `src/main/resources/ragDatabase/`（支持 .txt / .md / .markdown / .pdf）

4. 编译启动

```bash
mvn clean compile
mvn spring-boot:run
```

## 使用说明

### 对话接口

```
GET /test/agent/{sessionId}/{message}
```

| 参数 | 说明 |
|------|------|
| sessionId | 会话 ID，用于多轮对话记忆隔离 |
| message | 用户问题 |

示例：`GET /test/agent/session-001/公司有什么产品`，响应为流式纯文本，逐块返回 LLM 生成的回答。

### 知识库管理

- 文档放在 `src/main/resources/ragDatabase/` 目录下
- `rag.elasticsearch.delete-on-startup: true` 时每次启动重建索引，生产环境建议设为 `false`
- 系统提示词在 `application.yaml` 的 `ai.prompt.system-message` 中配置，改完重启生效，不用重新编译

## 参与贡献

1. Fork 本仓库
2. 新建 Feat_xxx 分支
3. 提交代码
4. 新建 Pull Request

## License

MIT License

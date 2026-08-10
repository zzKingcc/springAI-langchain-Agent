# springAI-Langchain-Agent

A RAG intelligent customer service system built with Spring Boot 3.5 + LangChain4j 1.18.1. Centered on Agent orchestration, it lets the LLM decide for itself when to search the knowledge base and when to call tools — a single dialog entry covers business Q&A, weather queries, and everyday chitchat.

## Highlights

### Agent Self-Orchestration

No dialog flow is hardcoded. The LLM reads tool descriptions and judges whether the current question needs retrieval, which tool to call, and in what order. Asking "what products does the company offer" triggers a knowledge-base search; asking "do I need an umbrella today" calls the weather tool; a plain greeting gets answered directly — all with zero if-else branches. Adding a new tool is just one `@Tool` method away.

### Hybrid Retrieval + Score Fusion

Any single retrieval method has blind spots: vector search grasps semantics but misses exact model numbers, while keyword search nails exact terms but misses paraphrase. The system runs both paths in parallel — vector Top15 and keyword Top5 — min-max normalizes the scores, fuses them with a 0.6 / 0.4 weighting, then applies boosts for title and file-name hits before re-ranking, balancing recall and precision.

### Dual-Constraint Chat Memory

Limiting only message count lets long replies overflow the token window; limiting only tokens keeps too many short messages. `DualConstraintChatMemory` caps both message count (100) and token estimation (30K), trimming whichever bound is hit first while preserving the system prompt and recent turns. Memory lives in Redis, isolated by `sessionId`, so sessions survive service restarts.

### Streaming Output

The model streams out as it generates, never making the user wait for a full reply. `StreamingChatModel` + `TokenStream` push first-token latency down to the sub-second range, so the experience feels like a live agent typing back rather than staring at a blank spinner.

### Input Security

Before a request reaches the model, `InputSanitizer` scans user input with five-dimensional regex covering instruction override, role confusion, delimiter injection, prompt extraction, and encoding bypass — five classes of prompt-injection attacks. Any hit is blocked outright, never passed to the model layer.

### Smart Document Ingestion

Different file types route to different processing strategies. Chinese documents get section-aware splitting, then two layers of deduplication strip repeated fragments, and batches of 25 are sent to the embedding API in compliance with model limits. Drop in txt, md, or pdf files and they get auto-digested into the knowledge base.

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Framework | Spring Boot | 3.5.7 |
| AI Framework | LangChain4j | 1.18.1 |
| LLM | Qwen qwen3.7-plus (Alibaba Bailian) | - |
| Embedding | text-embedding-v2 (1536 dims) | - |
| Vector Store | Elasticsearch | 9.4.4 |
| Chat Memory | Redis | - |
| JDK | OpenJDK | 21 |

## Architecture

```
User Request (GET /test/agent/{sessionId}/{message})
    |
    v
TestController --- InputSanitizer (input security detection)
    |
    v
AgentOrchestrationService (LangChain4j AiServices)
    |  +-------------------------------------------+
    |  | Injected: StreamingChatModel / memory / tools / prompt |
    |  +-------------------------------------------+
    |
    +-- LLM autonomously decides @Tool calls
    |   +-- searchKnowledgeBase -> vector+keyword hybrid retrieval -> fusion re-rank
    |   +-- queryWeather / queryMaxTemperature / ... (weather tools)
    |
    +-- Chat memory read/write (isolated by sessionId)
    |
    v
TokenStream -> Flux<String> -> HTTP response
```

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.8+
- Elasticsearch 9.4.4 (with IK analyzer plugin)
- Redis 6+
- Alibaba Bailian API Key (with qwen3.7-plus and text-embedding-v2 model access)

### Installation

1. Clone the repository

```bash
git clone https://gitee.com/your-username/springAI-Langchain-Agent.git
cd springAI-Langchain-Agent
```

2. Configure `src/main/resources/application.yaml` with required parameters:

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

3. Place knowledge base documents in `src/main/resources/ragDatabase/` (supports .txt / .md / .markdown / .pdf)

4. Build and run

```bash
mvn clean compile
mvn spring-boot:run
```

## Usage

### Chat API

```
GET /test/agent/{sessionId}/{message}
```

| Parameter | Description |
|-----------|-------------|
| sessionId | Session ID for multi-turn conversation memory isolation |
| message | User question |

Example: `GET /test/agent/session-001/公司有什么产品` — the response is streaming plain text, returning the LLM-generated answer chunk by chunk.

### Knowledge Base Management

- Place documents in `src/main/resources/ragDatabase/`
- `rag.elasticsearch.delete-on-startup: true` rebuilds the index on every startup; for production set it to `false`
- The system prompt is configured in `application.yaml` under `ai.prompt.system-message` — change it and restart, no recompilation needed

## Contributing

1. Fork the repository
2. Create a Feat_xxx branch
3. Commit your code
4. Create a Pull Request

## License

MIT License

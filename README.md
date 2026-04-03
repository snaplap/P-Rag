# P-Rag

本项目是企业级 RAG 智能问答示例，采用 Spring Boot + MyBatis 架构，包含内嵌前端页面，可直接在浏览器上传 Markdown 并进行流式对话。

## 你现在可以做什么

1. 启动后端后直接访问首页 UI（无需额外前端工程）
2. 在页面上传 Markdown 文件，自动切片并向量化
3. 每次上传会自动创建独立知识库（KB）和新会话
4. 页面会保存并展示上传痕迹，可切换历史 KB 继续对话
5. 在页面输入问题，接收 SSE 流式回答
6. 查看回答来源（知识库/联网）、不确定性与 MindMap 调用指令

## 一键准备中间件（Docker）

项目根目录已提供 `docker-compose.yml`，包含：

1. Redis
2. Milvus Standalone（含 etcd + minio）

启动命令：

```bash
docker compose up -d
```

## 启动项目

```bash
mvn spring-boot:run
```

启动后打开：

```text
http://localhost:8080/
```

## 配置防回退说明（重要）

如果你修改的是 `target/classes/application.yml`，运行后会被重新覆盖，这是正常现象：

1. `target/classes` 属于构建产物目录
2. 每次编译/运行时，Maven 会把 `src/main/resources/application.yml` 重新复制到该目录

推荐做法：

1. 修改 `src/main/resources/application.yml`
2. 私密配置写在项目根目录 `config/application-secrets.yml`
3. `config/application-secrets.yml` 可手动创建，内容只保留你要覆盖的键

这样可以避免每次运行配置被覆盖，也能避免把 API Key 硬编码进源码。

## 主要能力

1. 缓存优先：先查 Redis，命中直接返回
2. 检索增强：向量检索 + 轻量重排
3. 无命中兜底：低相关触发 MCP 联网搜索（当前为 Mock 实现）
4. 可追溯回答：严格基于证据生成并标注来源
5. 质量评估：返回命中状态、幻觉风险、可追溯分
6. 流式响应：SSE 增量文本 + final 事件
7. 思维导图：final 事件内包含 MindMap 调用指令

## API

### 上传 Markdown

`POST /api/rag/ingest/markdown`

- form-data: `file` (Markdown)
- form-data: `documentId` (可选)

返回示例（已包含新知识库会话信息）：

```json
{
  "knowledgeBaseId": "kb-58c1f1b7c923",
  "sessionId": "session-kb-58c1f1b7c923",
  "documentId": "2f4c7d4a-...",
  "fileName": "manual.md",
  "chunkCount": 12,
  "message": "markdown ingestion completed"
}
```

### 查询上传痕迹

`GET /api/rag/knowledge-bases`

- 返回历史上传记录（knowledgeBaseId、sessionId、fileName、chunkCount 等）

### 非流式问答

`POST /api/rag/query`

```json
{
  "question": "什么是 RAG？",
  "sessionId": "u-1001",
  "knowledgeBaseId": "kb-58c1f1b7c923",
  "topK": 5
}
```

### 流式问答

`POST /api/rag/query/stream`

- content-type: `application/json`
- response: `text/event-stream`
- 事件：`chunk` + `final`

## Docker 配置参数

`src/main/resources/application.yml` 支持通过环境变量覆盖：

1. `REDIS_HOST` / `REDIS_PORT`
2. `MILVUS_USE_REMOTE`
3. `MILVUS_BASE_URL`
4. `MILVUS_COLLECTION`
5. `LLM_PROVIDER`
6. `LLM_BASE_URL`
7. `LLM_API_KEY`
8. `LLM_MODEL`
9. `EMBEDDING_MODEL`

其中大模型配置路径位于 `app.rag.llm.*`，可在 `src/main/resources/application.yml` 中直接查看和修改。

默认即对应本机 Docker 端口映射：Redis `6379`，Milvus `19530`。

## 说明

1. 当前 Milvus 远端调用保留了扩展点，默认仍有本地回退索引，方便开发和联调。
2. 当前 MCP 联网搜索与 MindMap 为 Mock 实现，后续可切换真实 MCP 服务。
3. 前端默认隐藏检索分数、缓存命中、MindMap 指令等运行细节，用户只看到自然语言回答。

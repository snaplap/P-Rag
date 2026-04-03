# P-Rag

本项目是企业级 RAG 智能问答示例，采用 Spring Boot + MyBatis 架构，包含内嵌前端页面，可直接在浏览器上传 Markdown 并进行流式对话。

## 你现在可以做什么

1. 启动后端后直接访问首页 UI（无需额外前端工程）
2. 在页面上传 Markdown 文件，自动切片并向量化
3. 在页面输入问题，接收 SSE 流式回答
4. 查看回答来源（知识库/联网）、不确定性与 MindMap 调用指令

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

### 非流式问答

`POST /api/rag/query`

```json
{
  "question": "什么是 RAG？",
  "sessionId": "u-1001",
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

默认即对应本机 Docker 端口映射：Redis `6379`，Milvus `19530`。

## 说明

1. 当前 Milvus 远端调用保留了扩展点，默认仍有本地回退索引，方便开发和联调。
2. 当前 MCP 联网搜索与 MindMap 为 Mock 实现，后续可切换真实 MCP 服务。

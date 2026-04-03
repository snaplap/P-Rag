# P-Rag (Start Implementation)

本项目已完成第一批企业级 RAG Agent 落地骨架，技术栈：

- Spring Boot + Spring MVC + MyBatis
- Milvus 适配向量检索（首版包含本地回退索引，支持后续接入远端 Milvus）
- Redis 热点缓存（含本地回退）
- SSE 流式输出
- MCP 工具调用（首版为 Mock：联网搜索 + MindMap 指令）

## 已实现能力

1. 缓存优先：先查 Redis，命中直接返回
2. RAG 检索：向量检索 + 重排序
3. 无命中兜底：低相关或无命中时自动走 MCP 联网搜索
4. 严格溯源回答：答案仅基于检索证据，并标注数据来源（知识库/联网）
5. 质量评估：返回命中情况、幻觉风险、可追溯评分
6. Markdown 摄入：上传 Markdown，切片后向量化入库
7. 单轮上下文：保留必要历史（可配置窗口）
8. MindMap 输出：最终结果包含 MCP MindMap 指令

## API

### 1) Markdown 摄入

`POST /api/rag/ingest/markdown`

- form-data: `file` (Markdown 文件)
- form-data: `documentId` (可选)

### 2) 非流式问答

`POST /api/rag/query`

```json
{
  "question": "什么是 RAG？",
  "sessionId": "u-1001",
  "topK": 5
}
```

### 3) 流式问答

`POST /api/rag/query/stream`

- content-type: `application/json`
- response: `text/event-stream`
- 事件：`chunk`（文本增量）+ `final`（最终元数据 + MindMap 指令）

## 关键配置

见 `src/main/resources/application.yml`：

- `app.rag.retrieval.min-score`：低相关阈值
- `app.rag.cache.ttl-seconds`：缓存 TTL
- `app.rag.session.max-history`：必要历史轮次
- `app.rag.chunking.max-chars`：切片长度
- `app.rag.milvus.use-remote`：是否启用远端 Milvus
- `app.rag.mcp.use-mock`：是否使用 MCP Mock

## 下一步建议

1. 将 `MilvusVectorStore` 的远端写入/查询占位替换为真实 Milvus API
2. 将 `MockMcpToolClient` 替换为真实 MCP JSON-RPC 客户端
3. 接入真实国产云 LLM 与 Embedding 模型
4. 增加端到端测试（摄入 -> 检索 -> 生成 -> 流式）

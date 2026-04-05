# P-Rag

一个可直接运行的 RAG 示例项目：

- 后端：Spring Boot
- 前端：内置静态页面（无需单独前端工程）
- 中间件：Redis + Milvus
- 能力：Markdown 上传、向量检索、流式回答、联网兜底、质量评估、思维导图指令

---

## 1. 5 分钟快速上手

### 第一步：启动中间件

项目根目录执行：

```bash
docker compose up -d
```

默认会启动：

- Redis（6379）
- Milvus Standalone（19530）

### 第二步：启动应用

```bash
mvn spring-boot:run
```

### 第三步：打开页面

浏览器访问：

```text
http://localhost:8080/
```

### 第四步：实际体验一轮

1. 上传一个 Markdown 文件
2. 页面会自动创建一个知识库（knowledgeBaseId）
3. 输入问题并提问
4. 观察流式回答和来源信息

到这里，你已经完成完整链路：上传 -> 切片 -> 向量化 -> 检索 -> 生成。

---

## 2. 最小配置说明

配置文件在：

- `src/main/resources/application.yml`

常用环境变量：

1. `REDIS_HOST`
2. `REDIS_PORT`
3. `MILVUS_USE_REMOTE`
4. `MILVUS_BASE_URL`
5. `MILVUS_COLLECTION`
6. `LLM_BASE_URL`
7. `LLM_API_KEY`
8. `LLM_MODEL`
9. `EMBEDDING_MODEL`

建议：

- 业务配置改 `src/main/resources/application.yml`
- 私密配置（如 API Key）写到你自己的外部配置文件，不要写死进源码

注意：

- 不要改 `target/classes/application.yml`，它会在构建时被覆盖。

---

## 3. API 快速调用

### 3.1 上传 Markdown

接口：`POST /api/rag/ingest/markdown`

```bash
curl -X POST http://localhost:8080/api/rag/ingest/markdown \
  -F "file=@./demo.md"
```

返回示例：

```json
{
  "knowledgeBaseId": "kb-58c1f1b7c923",
  "sessionId": "session-kb-58c1f1b7c923",
  "documentId": "2f4c7d4a-...",
  "fileName": "demo.md",
  "chunkCount": 12,
  "message": "markdown ingestion completed"
}
```

### 3.2 非流式问答

接口：`POST /api/rag/query`

```bash
curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -d "{\"question\":\"这篇文档主要讲了什么\",\"knowledgeBaseId\":\"kb-58c1f1b7c923\",\"topK\":5}"
```

### 3.3 流式问答（SSE）

接口：`POST /api/rag/query/stream`

- 返回 `text/event-stream`
- 事件类型：`chunk`（增量文本）与 `final`（最终结构化数据）

### 3.4 查询知识库列表

接口：`GET /api/rag/knowledge-bases`

### 3.5 删除知识库

接口：`DELETE /api/rag/knowledge-bases/{knowledgeBaseId}`

---

## 4. 项目目录速读

下面是最常看的目录：

- `src/main/java/com/zzp/rag/controller`：接口入口层
- `src/main/java/com/zzp/rag/service`：业务服务层（编排、检索、生成、缓存等）
- `src/main/java/com/zzp/rag/domain`：领域对象层（DTO、模型、追踪记录）
- `src/main/resources/static`：内置前端页面

如果你是第一次接手，建议阅读顺序：

1. `RagController`（先看入口）
2. `RagOrchestratorService`（再看主流程）
3. `retrieval` / `generation` / `mcp` 子包（看关键能力）

---

## 5. 常见问题与排查

### Q1：为什么我改了配置又“失效”？

你可能改的是 `target/classes` 下的文件。该目录是构建产物，运行会覆盖。

### Q2：回答里为什么会出现“不确定性声明”？

当证据不足或评估风险较高时，系统会主动提示不确定性，避免“看起来很自信但其实没依据”。

### Q3：MCP 看起来没生效？

默认支持回退，远端调用失败时会使用 mock 结果。可通过返回中的链路诊断字段确认是否发生回退。

### Q4：Milvus 挂了还能用吗？

当前实现有本地回退索引，开发环境可继续联调，但线上建议始终保证远端存储可用。

---

## 6. 开发与测试

运行全部测试：

```bash
mvn test
```

只跑某个测试类：

```bash
mvn -Dtest=MarkdownChunkerTest test
```

---

## 7. 当前实现边界

1. MCP 联网和导图支持真实调用，但也内置了 mock 回退
2. 质量指标以在线启发式为主，离线标注评估可后续增强
3. 前端默认优先用户体验，隐藏了多数底层运行细节

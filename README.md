# P-Rag

P-Rag 是一个面向企业知识问答场景的 RAG 示例工程，用于快速验证从文档入库、向量检索到流式回答与导图增强的完整链路。

## 1. 项目价值

- 面向业务：可用于知识助手 PoC 和内部演示。
- 面向研发：提供 Spring Boot + 向量检索 + 流式输出的可运行参考实现。
- 面向扩展：支持联网兜底、导图生成、质量评估等能力。

## 2. 技术栈

- Java 17+
- Spring Boot 3.x
- Spring AI（OpenAI Compatible）
- Milvus（向量库）
- Redis（缓存与会话）
- Maven
- Docker / Docker Compose

## 3. 5 分钟启动

### 3.1 环境准备

- JDK 17+
- Maven 3.9+
- Docker 与 Docker Compose v2

### 3.2 启动中间件（Docker 简版）

本仓库不强制绑定特定 compose 文件，请使用你本地的中间件编排文件。

```bash
docker compose pull
docker compose up -d
docker compose ps
```

至少保证以下服务可用：

- Redis（默认 6379）
- Milvus（默认 19530）

### 3.3 启动应用

```bash
mvn spring-boot:run
```

访问地址：

```text
http://localhost:8080/
```

## 4. 项目配置

### 4.1 配置位置

- 主配置文件：src/main/resources/application.yml
- 本地变量建议：项目根目录 .env

建议把环境差异配置放在 .env 或系统环境变量中，不要修改 target/classes 下构建产物文件。

### 4.2 最小必填配置

```bash
# LLM
LLM_API_KEY=your_api_key
LLM_MODEL=qwen-plus
LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# Milvus
MILVUS_USE_REMOTE=true
MILVUS_BASE_URL=http://localhost:19530
```

### 4.3 导图增强可选配置

```bash
MCP_USE_MOCK=false
MCP_DIAGRAM_URL=http://localhost:18081/mcp/diagram
MCP_ENABLE_KROKI_RELAY=true
MCP_KROKI_BASE_URL=http://localhost:18083
```

### 4.4 配置优先级

1. 系统环境变量
2. .env
3. application.yml 默认值

## 5. 启动后验证

### 5.1 健康检查

```bash
curl http://localhost:8080/api/rag/health
```

返回 status=UP 表示服务可用。

### 5.2 最小业务验收

1. 上传 Markdown 文档。
2. 提交问题并观察流式输出。
3. 检查回答结果中是否返回导图指令并在页面正常渲染。

## 6. 接口速览

- POST /api/rag/ingest/markdown
- POST /api/rag/query
- POST /api/rag/query/stream
- GET /api/rag/knowledge-bases
- DELETE /api/rag/knowledge-bases/{knowledgeBaseId}

## 7. 测试

运行全部测试：

```bash
mvn test
```

运行单个测试类：

```bash
mvn -Dtest=MarkdownChunkerTest test
```

## 8. 演示图占位说明

建议将演示截图放在 docs/images 目录，并在对外发布前补充以下三类截图：

- 上传与入库流程
- 流式问答过程
- 导图展示结果

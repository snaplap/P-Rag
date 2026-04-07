# P-Rag

P-Rag 是一个面向企业知识问答场景的 RAG 示例工程，用于打通从文档入库、向量检索到流式回答与导图增强的完整链路。

## 1. 项目背景

- 用于快速验证企业内部知识助手的 PoC 方案。
- 支持基于知识库内容进行检索增强问答。
- 支持导图能力，便于将回答内容结构化展示。

## 2. 技术栈

- Java 17+
- Spring Boot 3.x
- Spring AI（OpenAI Compatible）
- Milvus（向量库）
- Redis（缓存与会话）
- Maven
- Docker / Docker Compose

## 3. 启动教程

### 3.1 环境准备

- JDK 17+
- Maven 3.9+
- Docker 与 Docker Compose v2

### 3.2 启动中间件


本仓库不强制绑定特定 compose 文件，请使用你本地的中间件编排文件。

确保 Redis（默认 6379）和 Milvus（默认 19530）可用。

### 3.3 启动应用

```bash
mvn spring-boot:run
```

访问地址：

```text
http://localhost:8080/
```

## 4. 展示

  pictures\9761d520-7efb-4d34-ba5a-d80c8383e79b.png

  pictures\91221cab-5208-49d2-822c-56a92d817228.png

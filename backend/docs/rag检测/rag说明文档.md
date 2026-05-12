# RAG 说明文档

> ai-content-engine 项目 RAG（检索增强生成）功能完整说明

---

## 1. 概述

RAG（Retrieval-Augmented Generation，检索增强生成）是本项目的核心能力之一。在文章生成过程中，RAG 从用户上传的课程资料中检索相关内容，并将其作为上下文注入 LLM 的 Prompt，使生成的文章具备专业知识支撑，而非依赖模型自身可能产生幻觉的"记忆"。

**典型场景**：用户上传一门"人工智能导论"课程的讲义 → 创建知识库 → 选择"机器学习基础"选题生成文章 → 系统自动从讲义中检索相关段落，注入标题、大纲、正文三个生成环节的 Prompt 中。

---

## 2. 系统架构

```
┌──────────────┐     ┌─────────────────────────────────┐     ┌──────────┐
│  前端 Vue.js │────▶│  Spring Boot 3.5 (Java 21)       │────▶│  MySQL   │
│  Ant Design   │     │                                 │     │  8.x     │
└──────────────┘     │  ┌───────────────────────────┐  │     └──────────┘
                     │  │ RAG 核心模块               │  │
                     │  │  DocumentParser  文本解析   │  │
                     │  │  DocumentChunker 文本切片   │  │
                     │  │  EmbeddingUtils  相似度计算 │  │
                     │  │  RagService      检索服务   │  │
                     │  └───────────────────────────┘  │
                     │                                 │     ┌──────────┐
                     │  ┌───────────────────────────┐  │────▶│ DashScope│
                     │  │ 多智能体文章生成            │  │     │ (qwen-max│
                     │  │  TitleGeneratorAgent        │  │     │  LLM)    │
                     │  │  OutlineGeneratorAgent      │  │     └──────────┘
                     │  │  ContentGeneratorAgent      │  │
                     │  └───────────────────────────┘  │
                     └─────────────────────────────────┘
```

**当前版本（v1）采用纯内存关键词匹配，不使用向量数据库或 Embedding API。**

---

## 3. 文档处理流程

### 3.1 知识库创建

用户首先创建一个知识库（Knowledge Base），作为文档的容器：

```
POST /course/kb/create  { name, courseName, description }
```

### 3.2 文档上传与解析

上传文档到指定知识库，系统自动完成解析和切片：

```
POST /course/document/upload?kbId=xxx   multipart/form-data { file }
```

**DocumentParser** (`rag/DocumentParser.java:15`)
- 支持格式：`.txt`、`.md`（仅 UTF-8 纯文本）
- 文件大小限制：≤ 10MB
- 通过 `BufferedReader` 读取为完整字符串
- 校验内容非空

### 3.3 文本切片

**DocumentChunker** (`rag/DocumentChunker.java:17`)

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `chunkSize` | 600 字符 | 每个切片的字符数 |
| `overlap` | 100 字符 | 相邻切片的重叠字符数 |
| `step` | 500 字符 | 滑动窗口步长（chunkSize - overlap） |

**切片策略**：
- 先用正则 `\s+` 清洗多余空白字符
- 如果文本长度 ≤ chunkSize，作为单个切片
- 否则以 step=500 为步长滑动窗口，步长 ≤ 0 时兜底单切片
- 尾部残留片段长度 ≥ 10 才保留（避免无意义小片段）

### 3.4 数据持久化

上传流程（`CourseDocumentServiceImpl.uploadDocument()`）在一个 `@Transactional` 事务中完成：

1. 创建 `course_document` 记录（状态=PARSING）
2. 解析文件内容
3. 切片文本
4. 批量插入 `course_document_chunk` 记录
5. 更新文档状态=SUCCESS，更新知识库的 documentCount 和 chunkCount

---

## 4. 检索机制

### 4.1 相似度计算：关键词匹配（v1）

**EmbeddingUtils** (`rag/EmbeddingUtils.java:17`) 实现了基于字符串的文本相似度算法：

```
完全包含关系:
  content 包含 query       → score = 0.95
  query 包含 content       → score = 0.90

关键词命中 + 字符覆盖:
  1. 提取关键词：
     - 按空格分词（英文/词组）
     - 单个中文字符作为独立关键词（处理"人工智能发展"这类无空格中文）
  2. keywordScore = 关键词在 content 中的命中数 / 总关键词数
  3. charCoverage = query 中非空字符在 content 中出现的比例
  4. 综合分数 = keywordScore × 0.6 + charCoverage × 0.4（上限 1.0）
```

**特点**：纯 JVM 内存计算，无外部依赖，但无法理解语义相似性（如"机器学习"和"深度学习"被视为不相关）。

### 4.2 检索流程

**RagServiceImpl.retrieve()** (`service/impl/RagServiceImpl.java:58`)

1. 校验登录态、kbId、query 非空
2. topK 默认 5，最大 10
3. 校验知识库存在且属于当前用户
4. 从 MySQL 查询该 kbId 下所有切片（MyBatis-Flex 自动过滤逻辑删除）
5. 遍历每个切片，计算与 query 的相似度分数
6. 保留 score > 0 的结果（总切片数 ≤ topK 时保留所有做兜底）
7. 按 score 降序排序，取 topK 返回

### 4.3 上下文构造

**RagServiceImpl.buildRagContext()** (`service/impl/RagServiceImpl.java:117`)

将检索到的切片格式化为 LLM 可理解的上下文文本：

```
【课程知识库参考资料】
以下内容来自用户上传的课程资料，请在生成课程内容时优先参考：

[资料1 | 相关度：0.92]
<切片内容1>

[资料2 | 相关度：0.87]
<切片内容2>
...
```

- 总长度上限：4000 字符（防止 Prompt 过长超出 LLM 上下文窗口）
- 检索异常或无结果时返回空字符串 `""`（优雅降级，不中断生成）

---

## 5. 上下文注入：RAG 与 LLM 的集成

### 5.1 注入入口

`ArticleAsyncService.enrichStateWithRagContext()` (`service/ArticleAsyncService.java:419`)

每个文章生成阶段（标题/大纲/正文）开始时调用，逻辑：
1. 检查 `article.ragEnabled == 1` 且 `kbId` 不为空
2. 调用 `ragService.buildRagContextByUserId(kbId, topic, topK=5, userId)`
3. 将格式化后的上下文写入 `ArticleState.ragContext`
4. 异常时降级为空字符串

### 5.2 Prompt 模板

**RAG_CONTEXT_SECTION** (`constant/PromptConstant.java:14`)

```
【课程知识库参考资料】
{ragContext}
参考资料使用指引：
- 如果以上参考资料不为空，请优先结合参考资料中的专业知识进行创作
- 标题要适合选修课教学内容，体现课程知识体系的核心主题
- 如果参考资料为空，则按原始选题正常创作
- 不要编造参考资料中没有依据的专业事实、数据或结论
```

### 5.3 注入的 Agent

| Agent | Prompt | 注入位置 |
|-------|--------|----------|
| TitleGeneratorAgent | `AGENT1_TITLE_PROMPT` | 选题后：`选题：{topic}{ragContext}` |
| OutlineGeneratorAgent | `AGENT2_OUTLINE_PROMPT` | 描述段后：`{descriptionSection}{ragContext}` |
| ContentGeneratorAgent | `AGENT3_CONTENT_PROMPT` | 大纲后：`{outline}{ragContext}` |

**不参与 RAG 的 Agent**：ImageAnalyzerAgent（配图分析不需要课程知识）、ContentMergerAgent（内容合并不需要课程知识）。

### 5.4 两种执行模式

项目支持两种执行模式，RAG 注入逻辑一致：

| 模式 | 编排方式 | 配置项 |
|------|----------|--------|
| 多智能体编排 | `ArticleAgentOrchestrator` + Spring AI StateGraph | `article.agent.orchestrator.enabled=true` |
| 传统模式 | `ArticleAgentService` 直接调用 | 默认 |

两种模式下，`ragContext` 都通过 `ArticleState` 对象传递，Agent 内部通过 `buildRagContextSection()` 方法决定是否附加 RAG 片段。

---

## 6. 文章生成流程中的 RAG 时序

```
用户发起创作
  │
  ├─ [阶段1] 标题生成
  │    ├─ 读取 article (ragEnabled, kbId)
  │    ├─ enrichStateWithRagContext(state, article, topic)
  │    │    └─ ragService.buildRagContextByUserId(kbId, topic, topK=5)
  │    │         ├─ 查询 course_document_chunk WHERE kbId=xxx
  │    │         ├─ 遍历计算相似度
  │    │         ├─ 排序取 topK
  │    │         └─ 格式化为上下文字符串
  │    ├─ TitleGeneratorAgent 将 ragContext 注入 AGENT1_TITLE_PROMPT
  │    └─ DashScope LLM 生成 3-5 个标题方案
  │
  ├─ [用户选择标题]
  │
  ├─ [阶段2] 大纲生成
  │    ├─ enrichStateWithRagContext(state, article, topic)  ← 再次检索
  │    ├─ OutlineGeneratorAgent 注入 ragContext
  │    └─ DashScope LLM 生成大纲
  │
  ├─ [用户确认大纲]
  │
  └─ [阶段3] 正文生成
       ├─ enrichStateWithRagContext(state, article, topic)  ← 再次检索
       ├─ ContentGeneratorAgent 注入 ragContext
       └─ DashScope LLM 生成正文
```

**关键设计**：每个阶段都会**重新检索**一次 RAG 上下文，因为不同阶段的 query（topic）相同，但这也为后续改进（如按章节标题检索）预留了入口。

---

## 7. 数据库设计

### 7.1 course_knowledge_base（知识库表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| kbId | VARCHAR(64) UNIQUE | UUID 知识库标识 |
| userId | BIGINT INDEX | 所属用户 |
| name | VARCHAR(128) | 知识库名称 |
| courseName | VARCHAR(128) | 课程名称 |
| description | TEXT | 描述 |
| status | VARCHAR(32) | NORMAL / DISABLED |
| documentCount | INT | 文档数 |
| chunkCount | INT | 切片总数 |
| createTime / updateTime | DATETIME | 时间戳 |
| isDelete | TINYINT | 逻辑删除 |

### 7.2 course_document（文档表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| docId | VARCHAR(64) UNIQUE | UUID 文档标识 |
| kbId | VARCHAR(64) INDEX | 所属知识库 |
| userId | BIGINT | 上传用户 |
| fileName | VARCHAR(255) | 文件名 |
| fileUrl | VARCHAR(512) | 存储路径 |
| fileType | VARCHAR(32) | txt / md |
| fileSize | BIGINT | 字节数 |
| parseStatus | VARCHAR(32) | PENDING / PARSING / SUCCESS / FAILED |
| parseError | VARCHAR(512) | 解析错误信息 |
| chunkCount | INT | 切片数 |
| createTime / updateTime | DATETIME | 时间戳 |
| isDelete | TINYINT | 逻辑删除 |

### 7.3 course_document_chunk（切片表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 主键 |
| chunkId | VARCHAR(64) UNIQUE | UUID 切片标识 |
| kbId | VARCHAR(64) INDEX | 所属知识库 |
| docId | VARCHAR(64) INDEX | 所属文档 |
| chunkIndex | INT | 切片序号 |
| content | TEXT | 切片文本内容 |
| tokenCount | INT | 近似 token 数（=content.length()） |
| embedding | TEXT | 向量嵌入（预留，当前为空） |
| createTime / updateTime | DATETIME | 时间戳 |
| isDelete | TINYINT | 逻辑删除 |

### 7.4 article（文章表，RAG 相关字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| kbId | VARCHAR(64) | 关联知识库 ID |
| ragEnabled | TINYINT | RAG 开关（0=关闭, 1=开启） |

---

## 8. API 接口

### 8.1 知识库管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/course/kb/create` | 创建知识库 |
| GET | `/course/kb/list` | 查询当前用户的知识库列表 |

### 8.2 文档管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/course/document/upload?kbId=xxx` | 上传文档（multipart/form-data） |
| GET | `/course/document/list?kbId=xxx` | 查询知识库下文档列表 |

### 8.3 RAG 测试

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/rag/search` | RAG 检索测试 `{ kbId, query, topK }` |

### 8.4 文章创作（RAG 入口）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/article/create` | 创建文章 `{ topic, style, ragEnabled, kbId }` |

---

## 9. 当前限制与后续改进

### 9.1 v1 版本限制

| 维度 | 限制 | 影响 |
|------|------|------|
| 检索方式 | 关键词重叠匹配 | 无法理解语义相似，"AI" 和 "人工智能" 视为无关 |
| 检索范围 | 全表扫描所有切片 | 切片数量大时性能下降 |
| 文档格式 | 仅 txt/md | 不支持 PDF、Word 等常见格式 |
| 检索粒度 | 固定 600 字符切片 | 上下文可能不完整，关键信息可能被截断 |
| 向量存储 | 未实现 | embedding 字段预留但未使用 |

### 9.2 规划中的改进（详见 `rag改进v1版本.md`）

- **DashScope Embedding API 集成**：替换关键词匹配为真实语义向量 + 余弦相似度
- **RAG 模式扩展**：新增 `RagModeEnum`（OFF / ON / AUTO），支持自动判断是否启用 RAG
- **智能知识库选择**：`KnowledgeBaseRouterService` 自动匹配最相关的知识库
- **切片策略优化**：支持按段落/语义边界切分，而非固定字符数
- **向量数据库**：切片量 > 5000 时引入 PGVector 或 Elasticsearch

---

## 10. 核心文件索引

| 模块 | 文件路径 |
|------|----------|
| 文本解析 | `src/main/java/com/yupi/template/rag/DocumentParser.java` |
| 文本切片 | `src/main/java/com/yupi/template/rag/DocumentChunker.java` |
| 相似度计算 | `src/main/java/com/yupi/template/rag/EmbeddingUtils.java` |
| 检索服务接口 | `src/main/java/com/yupi/template/service/RagService.java` |
| 检索服务实现 | `src/main/java/com/yupi/template/service/impl/RagServiceImpl.java` |
| RAG 注入入口 | `src/main/java/com/yupi/template/service/ArticleAsyncService.java` |
| Prompt 模板 | `src/main/java/com/yupi/template/constant/PromptConstant.java` |
| 标题 Agent | `src/main/java/com/yupi/template/agent/agents/TitleGeneratorAgent.java` |
| 大纲 Agent | `src/main/java/com/yupi/template/agent/agents/OutlineGeneratorAgent.java` |
| 正文 Agent | `src/main/java/com/yupi/template/agent/agents/ContentGeneratorAgent.java` |
| 文档上传服务 | `src/main/java/com/yupi/template/service/impl/CourseDocumentServiceImpl.java` |
| 数据库初始化 | `sql/rag_init.sql` |
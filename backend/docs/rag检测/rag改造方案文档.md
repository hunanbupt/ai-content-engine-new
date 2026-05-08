

# RAG 知识增强模块改造方案文档

> 项目：AI 爆款文章创作器（ai-passage-creator）
> 目标：在现有文章生成流程中，外挂 RAG 知识库，实现"面向课程选修课内容创作的知识增强生成"
> 版本：第一版最小可运行方案（MVP）

---

## 一、当前项目是否适合接入 RAG 的分析判断

### 1.1 当前文章生成流程中，RAG 应该接入在哪个阶段？

**结论：应在标题生成、大纲生成、正文生成三个阶段的 Prompt 构建前分别注入 RAG 检索结果。**

理由：
- 标题生成需要了解课程核心知识点，才能生成贴合的标题方案
- 大纲生成需要了解课程知识体系结构，才能设计合理的章节和要点
- 正文生成需要具体的知识点内容，才能写出有深度的教学内容
- **配图分析和配图生成阶段暂不需要 RAG**（配图关注的是正文中的插图需求，与课程知识关联度低）

### 1.2 是在创建 Article 任务时先检索一次，还是在每个 Agent 执行前分别检索？

**结论：采用"提前检索 + 各 Agent 按需使用"的混合策略。**

具体做法：
- 创建文章任务时，如果 ragEnabled=true，在 ArticleState 初始化阶段统一执行一次 RAG 检索
- 将检索结果（ragContext）存入 ArticleState，后续每个 Agent 从 ArticleState 中读取并使用
- 各 Agent 的 Prompt 构建时，将 ragContext 拼接到 Prompt 中

**为什么不是每个 Agent 分别检索？**
- 第一版中，三个 Agent（标题、大纲、正文）对检索的需求维度不同但数据源相同（同一课程知识库）
- 统一检索可降低 API 调用次数和 embedding 计算开销
- 通过传入不同的 topK 值来控制注入的上下文长度（标题阶段少些，正文阶段多些）
- 后续版本可以优化为每个 Agent 独立检索不同的 topK 和过滤策略

### 1.3 ArticleState 是否适合作为 ragContext 的传递载体？

**结论：非常适合。**

理由：
- ArticleState 本身就是多智能体间的共享状态容器，已在标题、大纲、正文三个 Agent 间传递
- 现有字段如 `topic`、`title`、`outline`、`content` 等都是通过 ArticleState 传递的
- ragContext 与这些字段性质一致——都是"跨阶段共享的上下文数据"
- 只需在 ArticleState 中新增一个 `ragContext` 字段（String 类型），序列化/反序列化无额外成本
- 现有 StateGraph 的 KeyStrategy 为 ReplaceStrategy，自动支持新字段的透传

### 1.4 现有 SSE 流式输出逻辑是否需要改动？

**结论：第一版不需要改动 SSE。**

理由：
- RAG 检索发生在 Agent 执行 **之前**，不是流式过程的一部分
- ragContext 注入 Prompt 后，整个流式生成流程与原来完全一致
- 前端无需感知 RAG 的存在（除了创建任务时多传 ragEnabled 和 kbId 参数）
- SSE 消息类型枚举 SseMessageTypeEnum 无需新增

### 1.5 现有 PromptConstant 或 Agent 类是否适合扩展 RAG Prompt？

**结论：适合，且改动量小。**

理由：
- PromptConstant 当前使用接口常量 + `{placeholder}` 占位符替换模式
- 只需新增 `{ragContext}` 占位符，在字符串模板末尾追加即可
- 例如：标题 Prompt 末尾追加 `{ragContext}` 段
- Agent 类（TitleGeneratorAgent、OutlineGeneratorAgent、ContentGeneratorAgent 等）只需在构建 Prompt 时多一次 `.replace("{ragContext}", ...)`
- ArticleAgentService 中对应的三个方法也需要同步修改

### 1.6 是否需要真正接入向量数据库？

**结论：第一版不需要，使用 MemoryVectorStore 即可。**

理由：
- Spring AI Alibaba 提供了开箱即用的 `SimpleVectorStore`（内存版），无需额外依赖
- 可以立即跑通 RAG 全流程：文档上传 → 切片 → embedding → 存储 → 检索 → 注入 Prompt
- 避免引入 PGVector/Redis Stack/Elasticsearch 等外部中间件的部署复杂度
- embedding 模型优先使用现有 DashScope 的 embedding API（项目已依赖 DashScope Starter）
- 若 DashScope embedding 不可用，可使用简单文本相似度（TF-IDF / BM25）做第一版检索

---

## 二、完整 RAG 版文章生成执行流程

### 流程步骤详解（17 步）

```
步骤 1：用户创建课程知识库
├── 涉及类：CourseKnowledgeBaseController#createKnowledgeBase()
├── 涉及服务：CourseKnowledgeBaseServiceImpl#createKnowledgeBase()
├── 涉及表：course_knowledge_base（INSERT）
└── 说明：填写课程名称、描述，生成 kbId（UUID）

步骤 2：用户上传课程文档
├── 涉及类：CourseDocumentController#uploadDocument()
├── 涉及服务：CourseDocumentServiceImpl#uploadDocument()
├── 涉及表：course_document（INSERT）
└── 说明：上传 txt/md 文件，保存到服务器本地临时目录

步骤 3：后端解析文档内容
├── 涉及类：rag/DocumentParser#parse()
├── 说明：读取文件内容，按文件类型（txt/md）解析为纯文本字符串

步骤 4：文档切片
├── 涉及类：rag/DocumentChunker#split()
├── 说明：按固定大小（如 500 字）+ 重叠（如 100 字）策略切分为多个 chunk
└── 每个 chunk 记录：chunkIndex、content、documentId、kbId

步骤 5：生成 embedding
├── 涉及类：rag/EmbeddingUtils#embed()
├── 方案A（优先）：调用 DashScope Embedding API 生成向量
└── 方案B（降级）：使用文本长度/关键词匹配做 mock embedding

步骤 6：写入向量存储
├── 涉及类：MemoryVectorStoreServiceImpl#store()
├── 涉及表：course_document_chunk（INSERT，保存 chunk 内容 + embedding JSON）
└── 说明：chunk 文本和 embedding 向量均持久化到数据库，MemoryVectorStore 启动时从 DB 加载

步骤 7：用户创建文章生成任务，选择 kbId
├── 涉及类：ArticleController#createArticle()（改造）
├── 涉及请求：ArticleCreateRequest（新增 ragEnabled、kbId 字段）
├── 涉及服务：ArticleServiceImpl#createArticleTaskWithQuotaCheck()
└── 涉及表：article（INSERT，包含 kbId、ragEnabled 字段）

步骤 8：后端根据 topic 检索知识库
├── 涉及类：RagServiceImpl#search()
├── 说明：将 topic 转为 embedding，计算与各 chunk 的余弦相似度，取 topK（默认5）
├── 或：使用简单文本关键词匹配（BM25/TF-IDF 降级方案）
└── 返回：List<RetrievedChunk>（包含 chunk 内容和相似度分数）

步骤 9：构造 ragContext
├── 涉及类：RagServiceImpl#buildRagContext()
├── 说明：将检索到的 chunks 格式化为一段提示词文本
└── 格式示例：
    """
    【课程知识点参考资料】
    以下是与你选题相关的课程知识内容，请在创作时参考：

    知识点1（相关度: 0.92）：
    XXX内容XXX

    知识点2（相关度: 0.87）：
    XXX内容XXX
    ...
    请基于以上知识点进行创作，确保内容的准确性和专业性。
    """

步骤 10：将 ragContext 写入 ArticleState
├── 涉及类：ArticleAsyncService#executePhase1()
├── 说明：在创建 ArticleState 后，判断 kbId 不为空则调用 RagService 检索，
│         并将 ragContext 设置到 state.setRagContext()
└── 如果检索失败：ragContext 设为空字符串，降级为普通生成

步骤 11：标题 Agent 结合 ragContext 生成标题
├── 涉及类：TitleGeneratorAgent#apply() / ArticleAgentService#agent1GenerateTitleOptions()
├── 涉及常量：PromptConstant#AGENT1_TITLE_PROMPT（新增 {ragContext} 占位符）
├── 说明：Prompt = AGENT1_TITLE_PROMPT.replace("{ragContext}", state.getRagContext())
└── 如果 ragContext 为空：Prompt = AGENT1_TITLE_PROMPT.replace("{ragContext}", "")

步骤 12：用户选择标题
├── 涉及类：ArticleController#confirmTitle()
└── 与原有流程完全一致，无变更

步骤 13：大纲 Agent 结合 ragContext 生成大纲
├── 涉及类：OutlineGeneratorAgent / ArticleAgentService#agent2GenerateOutline()
├── 涉及常量：PromptConstant#AGENT2_OUTLINE_PROMPT（新增 {ragContext} 占位符）
└── 同上，从 ArticleState 读取 ragContext 注入 Prompt

步骤 14：用户编辑或确认大纲
├── 涉及类：ArticleController#confirmOutline() / aiModifyOutline()
└── 与原有流程完全一致，无变更

步骤 15：正文 Agent 结合 ragContext 流式生成正文
├── 涉及类：ContentGeneratorAgent / ArticleAgentService#agent3GenerateContent()
├── 涉及常量：PromptConstant#AGENT3_CONTENT_PROMPT（新增 {ragContext} 占位符）
└── 同上，从 ArticleState 读取 ragContext 注入 Prompt

步骤 16：配图 Agent 继续原有流程
├── 涉及类：ImageAnalyzerAgent、ParallelImageGenerator、ContentMergerAgent
└── 第一版不做任何修改，保持原有逻辑

步骤 17：文章最终完成并落库
├── 涉及类：ArticleServiceImpl#saveArticleContent()
└── 与原有流程完全一致，ragContext 不落库（仅任务执行期间使用）
```

### 流程简化图示

```
用户创建知识库 → 上传文档 → 解析+切片 → Embedding → 存入向量库
                                                          ↓
用户创建文章(选kbId) → 检索知识库 → 构造ragContext → ArticleState
                                                          ↓
                                ┌─ 标题Agent(+ragContext) → 用户选标题
                                ├─ 大纲Agent(+ragContext) → 用户确认大纲
                                ├─ 正文Agent(+ragContext) → 流式SSE输出
                                ├─ 配图Agent(不改) → 图文合成(不改)
                                └─ 文章完成 + 落库
```

---

## 三、需要新增的项目目录结构

> 项目根包名：`com.yupi.template`（==/backend/src/main/java/com/yupi/template/==）

```
com.yupi.template
├── controller
│   ├── CourseKnowledgeBaseController.java    # 知识库 CRUD 接口
│   ├── CourseDocumentController.java         # 文档上传/查询接口
│   └── RagSearchController.java             # RAG 检索测试接口
├── service
│   ├── CourseKnowledgeBaseService.java       # 知识库服务接口
│   ├── CourseDocumentService.java            # 文档服务接口
│   └── RagService.java                      # RAG 检索服务接口
├── service/impl
│   ├── CourseKnowledgeBaseServiceImpl.java   # 知识库服务实现
│   ├── CourseDocumentServiceImpl.java        # 文档服务实现
│   └── RagServiceImpl.java                  # RAG 检索服务实现
├── mapper
│   ├── CourseKnowledgeBaseMapper.java        # 知识库 Mapper
│   ├── CourseDocumentMapper.java             # 文档 Mapper
│   └── CourseDocumentChunkMapper.java        # 切片 Mapper
├── model/entity
│   ├── CourseKnowledgeBase.java              # 知识库实体
│   ├── CourseDocument.java                   # 文档实体
│   └── CourseDocumentChunk.java              # 切片实体
├── model/dto/course
│   ├── CreateKnowledgeBaseRequest.java       # 创建知识库请求
│   ├── UploadCourseDocumentRequest.java      # 上传文档请求（用 MultipartFile）
│   ├── QueryCourseDocumentRequest.java       # 查询文档请求
│   └── KnowledgeBaseQueryRequest.java        # 知识库列表查询请求
├── model/dto/rag
│   ├── RagSearchRequest.java                # RAG 检索请求
│   └── RagSearchResponse.java               # RAG 检索响应
├── model/vo
│   ├── KnowledgeBaseVO.java                 # 知识库视图对象
│   ├── CourseDocumentVO.java                # 文档视图对象
│   └── RetrievedChunkVO.java               # 检索结果切片视图
├── model/enums
│   ├── KnowledgeBaseStatusEnum.java         # 知识库状态枚举（ACTIVE/INACTIVE）
│   └── DocumentParseStatusEnum.java         # 文档解析状态（PENDING/PARSING/COMPLETED/FAILED）
├── rag
│   ├── RetrievedChunk.java                  # 检索结果 POJO（chunk内容+相似度）
│   ├── DocumentParser.java                  # 文档解析器（txt/md → 纯文本）
│   ├── DocumentChunker.java                 # 文档切片器（固定大小+重叠）
│   └── EmbeddingUtils.java                  # Embedding 工具类（调用 DashScope API）
```

**设计说明：**
- 遵循现有项目分层：controller → service → service/impl → mapper → model/entity
- model/dto 下按业务模块拆分子包（course、rag），与现有项目风格一致（如现有 model/dto/article、model/dto/image）
- `rag/` 包放 RAG 核心算法组件（解析、切片、embedding），与业务服务解耦
- 不需要 VectorStoreService 接口，MemoryVectorStore 逻辑直接放在 RagServiceImpl 中（因为第一版只有一个实现）

---

## 四、数据库表设计

### 4.1 新增表

#### 表 1：course_knowledge_base（课程知识库）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| kbId | VARCHAR(64) | UNIQUE, NOT NULL | 知识库唯一标识（UUID） |
| userId | BIGINT | NOT NULL, INDEX | 创建者用户ID |
| name | VARCHAR(128) | NOT NULL | 知识库名称 |
| description | TEXT | NULL | 知识库描述 |
| status | VARCHAR(16) | NOT NULL, DEFAULT 'ACTIVE' | 状态：ACTIVE/INACTIVE |
| chunkCount | INT | DEFAULT 0 | 已存储的切片总数 |
| documentCount | INT | DEFAULT 0 | 已上传的文档总数 |
| createTime | DATETIME | NOT NULL | 创建时间 |
| updateTime | DATETIME | NOT NULL | 更新时间 |
| isDelete | TINYINT | DEFAULT 0 | 逻辑删除 |

**索引：**
- `idx_userId` (userId) — 按用户查询知识库列表
- `uk_kbId` (kbId) — 保证唯一性 + 精确查询

#### 表 2：course_document（课程文档）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| docId | VARCHAR(64) | UNIQUE, NOT NULL | 文档唯一标识（UUID） |
| kbId | VARCHAR(64) | NOT NULL, INDEX | 所属知识库ID |
| fileName | VARCHAR(256) | NOT NULL | 原始文件名 |
| fileType | VARCHAR(16) | NOT NULL | 文件类型：txt/md |
| fileSize | BIGINT | NOT NULL | 文件大小（字节） |
| content | LONGTEXT | NOT NULL | 解析后的原始文本内容 |
| parseStatus | VARCHAR(16) | NOT NULL | 解析状态：PARSING/COMPLETED/FAILED |
| chunkCount | INT | DEFAULT 0 | 该文档被切分的切片数 |
| errorMessage | TEXT | NULL | 解析失败时的错误信息 |
| createTime | DATETIME | NOT NULL | 上传时间 |
| updateTime | DATETIME | NOT NULL | 更新时间 |
| isDelete | TINYINT | DEFAULT 0 | 逻辑删除 |

**索引：**
- `uk_docId` (docId) — 保证唯一性
- `idx_kbId` (kbId) — 按知识库查询文档列表

#### 表 3：course_document_chunk（文档切片）

| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| chunkId | VARCHAR(64) | UNIQUE, NOT NULL | 切片唯一标识（UUID） |
| docId | VARCHAR(64) | NOT NULL, INDEX | 所属文档ID |
| kbId | VARCHAR(64) | NOT NULL, INDEX | 所属知识库ID（冗余，加速检索） |
| chunkIndex | INT | NOT NULL | 切片序号（从0开始） |
| content | TEXT | NOT NULL | 切片文本内容 |
| embedding | TEXT | NULL | embedding 向量（JSON 数组格式） |
| createTime | DATETIME | NOT NULL | 创建时间 |

**索引：**
- `uk_chunkId` (chunkId) — 保证唯一性
- `idx_docId` (docId) — 按文档查询切片
- `idx_kbId` (kbId) — 按知识库查询所有切片

### 4.2 现有 article 表新增字段

| 字段名 | 类型 | 默认值 | 说明 | 是否第一版必须 |
|--------|------|--------|------|----------------|
| kbId | VARCHAR(64) | NULL | 关联的知识库ID，NULL 表示不启用 RAG | **是** |
| ragEnabled | TINYINT(1) | 0 | 是否启用 RAG 知识增强 | **是** |
| ragContext | TEXT | NULL | RAG 检索结果文本（仅任务执行期间使用，完成后清空） | **否**（暂不加，存在 ArticleState 即可） |

**设计决策说明：**

| 字段 | 论证 |
|------|------|
| **kbId** | 必须第一版加，用于记录文章使用了哪个知识库。后续排查生成质量、统计分析都需要这个字段 |
| **ragEnabled** | 必须第一版加，用于前端控制和后端判断。虽然可以通过 kbId 是否为 null 来判断，但显式标识更清晰，避免歧义（kbId 存在但 RAG 检索失败时需要降级） |
| **ragContext** | 第一版建议不加。ragContext 是运行时临时数据，只在 ArticleState 中传递即可，不需要持久化到数据库。如果想做可回放或调试，后续版本再加 |

---

## 五、接口设计

### 5.1 新增接口

#### 接口 1：创建课程知识库

```
POST /api/course/kb/create
请求体：
{
  "name": "2025春季-数据结构与算法",
  "description": "计算机科学专业数据结构选修课讲义和资料"
}
响应：
{
  "code": 0,
  "data": "550e8400-e29b-41d4-a716-446655440000"  // kbId
}
```

#### 接口 2：查询我的知识库列表

```
GET /api/course/kb/my/list
响应：
{
  "code": 0,
  "data": [
    {
      "kbId": "550e8400-e29b-41d4-a716-446655440000",
      "name": "2025春季-数据结构与算法",
      "description": "...",
      "documentCount": 5,
      "chunkCount": 120,
      "status": "ACTIVE",
      "createTime": "2025-01-15T10:30:00"
    }
  ]
}
```

#### 接口 3：上传课程文档

```
POST /api/course/document/upload
Content-Type: multipart/form-data
参数：
  - kbId: String（必填）
  - file: MultipartFile（必填，仅支持 .txt / .md）
响应：
{
  "code": 0,
  "data": {
    "docId": "...",
    "fileName": "chapter1_intro.txt",
    "fileSize": 10240,
    "parseStatus": "COMPLETED",
    "chunkCount": 8
  }
}
```

#### 接口 4：查询知识库文档列表

```
GET /api/course/document/list?kbId=xxx
响应：
{
  "code": 0,
  "data": [
    {
      "docId": "...",
      "fileName": "chapter1_intro.txt",
      "fileSize": 10240,
      "parseStatus": "COMPLETED",
      "chunkCount": 8,
      "createTime": "2025-01-15T10:30:00"
    }
  ]
}
```

#### 接口 5：RAG 测试检索

```
POST /api/rag/search
请求体：
{
  "kbId": "550e8400-...",
  "query": "二叉树的遍历算法",
  "topK": 5
}
响应：
{
  "code": 0,
  "data": {
    "query": "二叉树的遍历算法",
    "results": [
      {
        "chunkId": "...",
        "content": "二叉树遍历有前序、中序、后序三种方式...",
        "score": 0.92,
        "docId": "...",
        "chunkIndex": 3
      }
    ]
  }
}
```

#### 接口 6：改造文章生成接口

**决策：复用原有创建文章接口，不新增独立接口。**

理由：
- 核心逻辑完全一致，仅仅是多传两个参数
- 新增独立接口会导致前端两个入口，增加维护成本
- 通过 `ragEnabled` 字段区分走 RAG 流程还是普通流程

```
POST /api/article/create（改造）
请求体（新增字段）：
{
  "topic": "二叉树遍历算法详解",
  "style": "educational",
  "enabledImageMethods": ["PEXELS"],
  "ragEnabled": true,        // ← 新增
  "kbId": "550e8400-..."     // ← 新增
}
响应：
{
  "code": 0,
  "data": "<taskId>"  // 不变
}
```

### 5.2 是否需要单独新增"课程文章生成接口"？

**结论：不需要。** 复用 `/api/article/create`，通过 `ragEnabled` 和 `kbId` 参数区分即可。后续如需课程专属的生成逻辑（如不同预设参数、不同模板），再考虑新增。

---

## 六、RAG 第一版实现范围

### 6.1 明确包含的功能

| 序号 | 功能 | 说明 |
|------|------|------|
| 1 | 文档类型 | **仅支持 txt 和 md**，pdf/docx 预留扩展点 |
| 2 | 文档上传 | 通过 MultipartFile 上传，保存原始文件，解析后存文本内容 |
| 3 | 文档解析 | DocumentParser 按文件扩展名分支处理 |
| 4 | 文档切片 | DocumentChunker：固定大小 500 字 + 重叠 100 字 |
| 5 | Embedding | 优先调用 DashScope Embedding API；如果不可用，降级为关键词匹配 |
| 6 | 向量存储 | **内存版 MemoryVectorStore**，启动时从 course_document_chunk 表加载 |
| 7 | RAG 检索 | topK 检索，余弦相似度排序 |
| 8 | ragContext 注入 | 注入到标题、大纲、正文三个 Agent 的 Prompt |
| 9 | 配图 Agent | **不改** |
| 10 | SSE | **不改** |
| 11 | 原有流程 | 无 RAG 时完全保持原有行为 |
| 12 | RAG 失败降级 | 检索失败时自动降级为普通生成，ragContext 设为空 |

### 6.2 明确暂不做的功能

| 序号 | 功能 | 后续版本考虑 |
|------|------|-------------|
| 1 | PDF、DOCX 文档解析 | 第二版引入 Apache PDFBox、Apache POI |
| 2 | 真实向量数据库 | 第二版引入 PGVector 或 Elasticsearch |
| 3 | 语义检索优化 | 检索重排序（rerank）、混合检索（BM25 + 向量） |
| 4 | 配图 Agent RAG | 配图场景与课程知识关联度低，暂不需要 |
| 5 | SSE RAG 状态推送 | 如果后续需要展示检索过程给用户 |
| 6 | 知识库编辑/删除 | 第一版仅创建和查询，编辑删除后续加 |
| 7 | 文档删除 | 第一版仅上传和解析，删除后续加 |

---

## 七、原有代码需要修改的位置

### 7.1 修改清单

| 序号 | 原有文件 | 修改内容 | 修改原因 | 是否必须 |
|------|----------|----------|----------|----------|
| 1 | `model/dto/article/ArticleCreateRequest.java` | 新增 `ragEnabled`（Boolean）和 `kbId`（String）字段 | 前端传入 RAG 参数 | **是** |
| 2 | `model/entity/Article.java` | 新增 `kbId`（String）、`ragEnabled`（Integer）字段及对应 `@Column` 注解 | 持久化 RAG 关联信息 | **是** |
| 3 | `model/dto/article/ArticleState.java` | 新增 `ragContext`（String）字段 | 在多智能体间传递 RAG 检索结果 | **是** |
| 4 | `service/ArticleService.java` | `createArticleTaskWithQuotaCheck` 方法签名新增 `ragEnabled` 和 `kbId` 参数 | 创建任务时保存 RAG 参数 | **是** |
| 5 | `service/impl/ArticleServiceImpl.java` | 实现上述方法改动，article 构造时设置 kbId/ragEnabled | 数据库写入 | **是** |
| 6 | `controller/ArticleController.java` | `createArticle` 方法中读取 `request.getRagEnabled()` 和 `request.getKbId()` 并传入 Service | 参数透传 | **是** |
| 7 | `service/ArticleAsyncService.java` | `executePhase1` 中，创建 ArticleState 后判断 kbId 不为空则调用 RagService 检索并设置 ragContext | RAG 检索入口 | **是** |
| 8 | `agent/agents/TitleGeneratorAgent.java` | 从 OverAllState 读取 ragContext，注入 Prompt 的 `{ragContext}` 占位符 | 标题 RAG 增强 | **是** |
| 9 | `agent/ArticleAgentOrchestrator.java` | 新增 `KEY_RAG_CONTEXT` 常量，executePhase1/2/3 的 inputs 中加入 ragContext | 状态图传递 | **是** |
| 10 | `constant/PromptConstant.java` | AGENT1/AGENT2/AGENT3 的 Prompt 模板中各新增 `{ragContext}` 占位符段 | Prompt 模板扩展 | **是** |
| 11 | `service/ArticleAgentService.java` | `agent1GenerateTitleOptions`、`agent2GenerateOutline`、`agent3GenerateContent` 三个方法中注入 ragContext | 原有模式 RAG 增强 | **是** |
| 12 | `model/enums/SseMessageTypeEnum.java` | 暂不修改 | 第一版无需 SSE 变更 | 否 |
| 13 | `manager/SseEmitterManager.java` | 暂不修改 | 第一版无需 SSE 变更 | 否 |
| 14 | `agent/agents/OutlineGeneratorAgent.java` | 从 OverAllState 读取 ragContext，注入 Prompt | 大纲 RAG 增强 | **是** |
| 15 | `agent/agents/ContentGeneratorAgent.java` | 从 OverAllState 读取 ragContext，注入 Prompt | 正文 RAG 增强 | **是** |

### 7.2 不需要修改的文件

| 文件 | 原因 |
|------|------|
| ImageAnalyzerAgent.java | 配图分析不涉及 RAG |
| ParallelImageGenerator.java | 配图生成不涉及 RAG |
| ContentMergerAgent.java | 图文合成不涉及 RAG |
| SseEmitterManager.java | SSE 推送逻辑不变 |
| SseMessageTypeEnum.java | 消息类型无需新增 |
| ArticlePhaseEnum.java | 阶段流转不变 |
| ArticleStatusEnum.java | 状态枚举不变 |

---

## 八、推荐开发顺序

> 适合分多次让 AI 编程助手（Cursor/Claude Code）实现的步骤顺序。

---

### 第 1 步：新增数据库表、Entity、Mapper、Service 空壳

**目标：** 搭建最基础的数据层架子，确保项目编译通过。

**具体工作：**
1. 创建执行 SQL 脚本（3 张新表 + 2 个 article 表新字段）
2. 创建实体类：`CourseKnowledgeBase.java`、`CourseDocument.java`、`CourseDocumentChunk.java`
3. 创建 Mapper 接口：`CourseKnowledgeBaseMapper.java`、`CourseDocumentMapper.java`、`CourseDocumentChunkMapper.java`
4. 创建枚举类：`KnowledgeBaseStatusEnum.java`、`DocumentParseStatusEnum.java`
5. 创建 Service 空接口和空实现（方法体留空或 return null）
6. **验证：** `mvn compile` 通过

**涉及文件：**
- `sql/rag_init.sql`（新建）
- `model/entity/CourseKnowledgeBase.java`
- `model/entity/CourseDocument.java`
- `model/entity/CourseDocumentChunk.java`
- `mapper/CourseKnowledgeBaseMapper.java`
- `mapper/CourseDocumentMapper.java`
- `mapper/CourseDocumentChunkMapper.java`
- `model/enums/KnowledgeBaseStatusEnum.java`
- `model/enums/DocumentParseStatusEnum.java`
- `service/CourseKnowledgeBaseService.java`
- `service/CourseDocumentService.java`
- `service/RagService.java`
- `service/impl/CourseKnowledgeBaseServiceImpl.java`
- `service/impl/CourseDocumentServiceImpl.java`
- `service/impl/RagServiceImpl.java`

---

### 第 2 步：实现知识库创建和查询

**目标：** 用户可以创建课程知识库，并查看自己的知识库列表。

**具体工作：**
1. 实现 `CourseKnowledgeBaseServiceImpl` 的创建和查询方法
2. 创建 DTO/VO：`CreateKnowledgeBaseRequest.java`、`KnowledgeBaseQueryRequest.java`、`KnowledgeBaseVO.java`
3. 实现 `CourseKnowledgeBaseController` 的创建和列表接口
4. **验证：** 通过 Swagger UI（`/api/doc.html`）测试创建和查询

**涉及文件：**
- `model/dto/course/CreateKnowledgeBaseRequest.java`
- `model/dto/course/KnowledgeBaseQueryRequest.java`
- `model/vo/KnowledgeBaseVO.java`
- `controller/CourseKnowledgeBaseController.java`
- `service/impl/CourseKnowledgeBaseServiceImpl.java`

---

### 第 3 步：实现文档上传、解析、切片

**目标：** 用户可以上传 txt/md 文档到指定知识库，系统自动解析并切片。

**具体工作：**
1. 实现 `DocumentParser.java`：按文件类型读取文本内容
2. 实现 `DocumentChunker.java`：500 字固定大小 + 100 字重叠切片
3. 实现 `CourseDocumentServiceImpl`：上传文件 → 保存 → 解析 → 切片 → 持久化
4. 实现 `CourseDocumentController` 的上传和列表接口
5. 创建 DTO/VO：`UploadCourseDocumentRequest.java`、`QueryCourseDocumentRequest.java`、`CourseDocumentVO.java`
6. **验证：** 上传一个测试 txt 文件，检查数据库 chunk 表已有切片数据

**涉及文件：**
- `rag/DocumentParser.java`
- `rag/DocumentChunker.java`
- `model/dto/course/UploadCourseDocumentRequest.java`
- `model/dto/course/QueryCourseDocumentRequest.java`
- `model/vo/CourseDocumentVO.java`
- `controller/CourseDocumentController.java`
- `service/impl/CourseDocumentServiceImpl.java`

---

### 第 4 步：实现 Embedding 和 MemoryVectorStore + RagService

**目标：** 实现从 course_document_chunk 表加载向量到内存，并支持检索。

**具体工作：**
1. 实现 `EmbeddingUtils.java`
   - 方案A（优先）：调用 DashScope Embedding API（项目已有 DashScope Starter）
   - 方案B（降级）：关键词重叠度评分（在 DashScope embedding 不可用时自动降级）
2. 实现 `RagServiceImpl`
   - 初始化时从 `course_document_chunk` 表加载所有 chunks 到内存 List
   - 对已有 embedding 的 chunk 使用余弦相似度
   - 对无 embedding 的 chunk 使用关键词匹配
   - `search(kbId, query, topK)` 方法
   - `buildRagContext(List<RetrievedChunk>)` 方法
3. 创建 DTO/VO：`RagSearchRequest.java`、`RagSearchResponse.java`、`RetrievedChunkVO.java`
4. 创建 `rag/RetrievedChunk.java` POJO
5. **验证：** 单元测试检索方法

**涉及文件：**
- `rag/EmbeddingUtils.java`
- `rag/RetrievedChunk.java`
- `model/dto/rag/RagSearchRequest.java`
- `model/dto/rag/RagSearchResponse.java`
- `model/vo/RetrievedChunkVO.java`
- `service/impl/RagServiceImpl.java`

---

### 第 5 步：实现 RAG 检索测试接口

**目标：** 通过接口验证 RAG 检索全链路跑通。

**具体工作：**
1. 实现 `RagSearchController` 的检索测试接口
2. **验证：** 通过 Swagger UI 输入 query 测试检索结果是否合理

**涉及文件：**
- `controller/RagSearchController.java`

---

### 第 6 步：改造 ArticleState 和 Article 创建请求

**目标：** Article 创建流程支持传入 RAG 参数，ArticleState 能携带 ragContext。

**具体工作：**
1. 修改 `ArticleCreateRequest`：新增 `ragEnabled`、`kbId`
2. 修改 `Article.java` 实体：新增 `kbId`、`ragEnabled` 字段
3. 修改 `ArticleState.java`：新增 `ragContext` 字段
4. 修改 `ArticleServiceImpl.createArticleTaskWithQuotaCheck`：保存新字段
5. 修改 `ArticleController.createArticle`：透传新参数
6. 执行数据库 ALTER TABLE 脚本
7. **验证：** 创建文章任务时传入 `ragEnabled=true`，检查 article 表已存储 kbId

**涉及文件：**
- `model/dto/article/ArticleCreateRequest.java`
- `model/entity/Article.java`
- `model/dto/article/ArticleState.java`
- `service/ArticleService.java`
- `service/impl/ArticleServiceImpl.java`
- `controller/ArticleController.java`

---

### 第 7 步：把 ragContext 接入标题、大纲、正文 Agent

**目标：** 核心 RAG 增强生效。

**具体工作：**
1. 修改 `PromptConstant.java`：三个 Prompt 模板各追加 `{ragContext}` 占位符
2. 修改 `ArticleAsyncService.executePhase1`：创建 ArticleState 后调用 RagService.search()，设置 ragContext
3. 修改 `ArticleAgentService` 的三个方法：Prompt 中 `.replace("{ragContext}", state.getRagContext())`
4. 修改 Agent 类（TitleGeneratorAgent、OutlineGeneratorAgent、ContentGeneratorAgent）：从 state 读取 ragContext
5. 修改 `ArticleAgentOrchestrator.java`：新增 KEY_RAG_CONTEXT，phase1/2/3 的 inputs 中传入
6. 实现降级逻辑：检索失败时 ragContext 为空字符串，走普通生成
7. **验证：** 创建带 kbId 的文章任务，检查生成的标题/大纲/正文是否纳入了课程知识

**涉及文件：**
- `constant/PromptConstant.java`
- `service/ArticleAsyncService.java`
- `service/ArticleAgentService.java`
- `agent/ArticleAgentOrchestrator.java`
- `agent/agents/TitleGeneratorAgent.java`
- `agent/agents/OutlineGeneratorAgent.java`
- `agent/agents/ContentGeneratorAgent.java`

---

### 第 8 步：测试普通生成不受影响

**目标：** 确保原有功能完全不受 RAG 改造影响。

**测试清单：**
1. 不传 ragEnabled，创建文章任务 → 标题生成 → 大纲生成 → 正文生成 → 配图 → 完成
2. 检查 SSE 流式输出是否正常
3. 检查标题选择、大纲编辑、大纲 AI 修改、阶段回退是否正常
4. 检查文章详情接口返回是否正常
5. 关注日志，确认无新增异常
6. **验证：** 全部通过即为回归测试通过

---

### 第 9 步：测试 RAG 生成流程 + 降级

**目标：** 验证 RAG 全流程和降级逻辑。

**测试清单：**
1. 创建知识库 → 上传文档 → 创建带 kbId 的文章任务 → 观察生成结果是否贴合课程内容
2. 上传不相关的文档 → 创建带 kbId 的任务 → 观察检索结果相关性
3. 创建带 kbId 但知识库为空的文章 → 应自动降级为普通生成（不报错）
4. Embedding API 不可用时 → 应自动降级为关键词匹配
5. 并发测试：同时创建多个带 RAG 的文章任务
6. **验证：** RAG 增强有效 + 降级不崩溃

---

## 九、最终结论

### 9.1 当前项目是否建议使用 RAG？

**建议使用。** 理由：

1. **需求匹配度高：** 项目已有成熟的文章生成流程和多智能体架构，"课程知识增强"是天然的 RAG 应用场景
2. **架构契合：** ArticleState 状态共享对象、StateGraph 编排、Prompt 模板替换机制都为 RAG 接入提供了良好条件
3. **改造成本低：** 现有代码只需在 3 个 Agent 的 Prompt 构建处增加 ragContext 替换，核心流程无需重构
4. **降级安全：** RAG 失败不影响原有生成能力，风险可控
5. **可渐进式交付：** 最小改动即可跑通，后续再逐步优化向量库和检索质量

### 9.2 第一版是否必须上真实向量数据库？

**不必。** MemoryVectorStore（从数据库加载到内存的简单向量存储）足够跑通第一版全流程。课程文档的规模通常在几百到几千个 chunks，完全在 JVM 堆内存可承受范围内。后续当单个知识库的 chunk 数超过 5000 时再考虑 PGVector。

### 9.3 是否需要修改 SSE？

**不需要。** RAG 检索发生在 Agent 执行之前，不产生流式数据。前端无需感知 RAG 的存在。

### 9.4 是否需要修改配图模块？

**不需要。** 第一版配图模块保持完全不变。后续如果出现"需要在配图分析中参考课程知识点来选择插图主题"的需求，再考虑接入。

### 9.5 是否建议先用 MemoryVectorStore 跑通？

**强烈建议。** 这是让 RAG 最快可见、最低风险、最少外部依赖的路径。跑通后如果效果不满意，替换为 PGVector 只需实现一个新的检索方法，代码架构无需变动。

### 9.6 下一步最适合生成的代码？

**推荐从第 1 步开始：新增数据库表、Entity、Mapper、Service 空壳。** 

理由：
- 这是整个 RAG 模块的基础脚手架，后续所有步骤都依赖它
- 代码结构固定、逻辑简单（纯模板代码），AI 生成质量最高
- 完成后项目编译通过，给后续步骤打好基础
- 这一步不涉及业务逻辑，改错的可能性最低

**之后依次推进第 2 → 3 → 4 → 5 → 6 → 7 步**，每一步都有明确的验证目标，可以独立测试通过后再进行下一步。
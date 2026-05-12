你现在是一个资深 Java 后端架构师，请先不要直接修改代码。

请你先完整阅读当前 Spring Boot 3 + Spring AI Alibaba AI 文章生成项目的代码结构，并基于现有项目能力，帮我设计“外挂 RAG 知识库”的改造方案。

当前项目已有能力：
1. 用户输入主题后，可以生成 AI 文章；
2. 系统采用多智能体流程，包括标题生成、大纲生成、正文生成、配图分析、图文合成；
3. 已经有 ArticleState 状态对象，用于在多个智能体之间传递标题、大纲、正文等中间结果；
4. 已经有 Article 表，保存 taskId、userId、topic、mainTitle、subTitle、outline、content、fullContent、coverImage、images、status、phase 等字段；
5. 已经通过 Spring AI Alibaba 调用大模型；
6. 正文生成阶段支持流式输出，并通过 SSE 推送给前端。

我现在想在原有项目基础上扩展一个 RAG 知识增强模块，应用场景是：
“面向课题中选修课内容创作的知识增强生成”。

也就是说，用户可以创建某门选修课的课程知识库，上传课程大纲、教学资料、知识点文档、案例材料等。系统在生成文章时，可以先从课程知识库中检索相关内容，再把检索结果注入到标题生成、大纲生成、正文生成等智能体 Prompt 中，从而生成更贴合课程知识体系的教学内容。

本轮任务请你只做方案设计，不要改代码。

请你重点输出以下内容：

一、先分析当前项目是否适合接入 RAG

请结合当前项目结构判断：
1. 当前文章生成流程中，RAG 应该接入在哪个阶段；
2. 是在创建 Article 任务时先检索，还是在每个 Agent 执行前分别检索；
3. ArticleState 是否适合作为 ragContext 的传递载体；
4. 现有 SSE 流式输出逻辑是否需要改动；
5. 现有 PromptConstant 或 Agent 类是否适合扩展 RAG Prompt；
6. 当前项目是否需要真正接入向量数据库，还是可以先使用内存版 MemoryVectorStore 跑通流程。

请给出你的判断结论：
- 是否建议接入 RAG；
- 为什么适合接入；
- 第一版建议做到什么程度；
- 哪些功能可以先不做。

二、设计整体执行流程

请给出完整的 RAG 版文章生成执行流程，要求按步骤说明。

示例格式：

1. 用户创建课程知识库；
2. 用户上传课程资料；
3. 后端解析文档；
4. 文档切片；
5. 生成 embedding；
6. 写入向量存储；
7. 用户创建文章生成任务，并选择 kbId；
8. 后端根据 topic 检索知识库；
9. 构造 ragContext；
10. 将 ragContext 写入 ArticleState；
11. 标题 Agent 结合 ragContext 生成标题；
12. 用户选择标题；
13. 大纲 Agent 结合 ragContext 生成大纲；
14. 用户编辑或确认大纲；
15. 正文 Agent 结合 ragContext 流式生成正文；
16. 配图 Agent 继续原有流程；
17. 文章最终完成并落库。

请说明每一步涉及哪些类、哪些方法、哪些表。

三、设计需要新增的项目目录结构

请根据当前项目风格，规划需要新增的文件夹和类。

请按照 Java 后端常见分层结构输出，例如：

src/main/java/xxx
├── controller
│   ├── CourseKnowledgeBaseController.java
│   ├── CourseDocumentController.java
│   └── RagController.java
├── service
│   ├── CourseKnowledgeBaseService.java
│   ├── CourseDocumentService.java
│   ├── CourseDocumentChunkService.java
│   ├── RagService.java
│   └── VectorStoreService.java
├── service/impl
│   ├── CourseKnowledgeBaseServiceImpl.java
│   ├── CourseDocumentServiceImpl.java
│   ├── CourseDocumentChunkServiceImpl.java
│   ├── RagServiceImpl.java
│   └── MemoryVectorStoreServiceImpl.java
├── mapper
│   ├── CourseKnowledgeBaseMapper.java
│   ├── CourseDocumentMapper.java
│   └── CourseDocumentChunkMapper.java
├── model/entity
│   ├── CourseKnowledgeBase.java
│   ├── CourseDocument.java
│   └── CourseDocumentChunk.java
├── model/dto/course
│   ├── CreateKnowledgeBaseRequest.java
│   ├── UploadCourseDocumentRequest.java
│   └── QueryCourseDocumentRequest.java
├── model/dto/rag
│   ├── RagSearchRequest.java
│   └── RagSearchResponse.java
├── model/vo
│   ├── KnowledgeBaseVO.java
│   ├── CourseDocumentVO.java
│   └── RetrievedChunkVO.java
├── model/enums
│   ├── KnowledgeBaseStatusEnum.java
│   └── DocumentParseStatusEnum.java
├── rag
│   ├── RetrievedChunk.java
│   ├── DocumentChunker.java
│   ├── DocumentParser.java
│   └── EmbeddingUtils.java

请根据我的项目实际包名来调整，不要生硬照搬。

四、设计数据库表

请先设计需要新增的数据库表，不要执行代码修改。

需要至少包括：

1. course_knowledge_base
   用于保存课程知识库。

2. course_document
   用于保存用户上传的课程文档。

3. course_document_chunk
   用于保存文档切片内容。

同时请判断 Article 表是否需要新增字段：
- kb_id
- rag_enabled
- rag_context

请说明这些字段的作用，以及是否必须第一版就加。

五、设计接口

请设计第一版需要新增的接口，要求包括请求路径、请求参数、响应数据和作用。

至少包括：

1. 创建课程知识库
   POST /api/course/kb/create

2. 查询我的知识库列表
   GET /api/course/kb/my/list

3. 上传课程文档
   POST /api/course/document/upload

4. 查询知识库文档列表
   GET /api/course/document/list?kbId=xxx

5. RAG 测试检索
   POST /api/rag/search

6. 改造文章生成接口
   在原有创建文章接口中新增：
- ragEnabled
- kbId

请判断是否需要单独新增一个“课程文章生成接口”，还是复用原有文章生成接口。

六、设计 RAG 第一版实现范围

请你给出第一版最小可运行方案。

要求：
1. 文档类型先只支持 txt 和 md；
2. pdf、docx 暂时不做，只预留扩展；
3. 向量库先使用内存版 MemoryVectorStore；
4. embedding 如果项目没有可用模型，可以先用 mock embedding 或简单文本相似度跑通流程；
5. 先实现 topK 检索；
6. 先把 ragContext 注入标题、大纲、正文三个 Agent；
7. 配图 Agent 暂时不改；
8. SSE 暂时不改；
9. 先保证原有文章生成流程不受影响；
10. RAG 失败时必须降级为普通生成。

七、设计原有代码需要修改的位置

请你根据项目代码结构指出哪些原有类需要修改。

重点检查：
1. Article 创建请求 DTO；
2. Article 实体类；
3. ArticleState；
4. ArticleService 或文章创建服务；
5. 多智能体执行类；
6. PromptConstant；
7. SSE 推送相关类是否需要修改；
8. 数据库 SQL 或 MyBatis-Plus 映射。

请用表格输出：
- 原有文件
- 修改内容
- 修改原因
- 是否必须修改

八、输出最终开发顺序

请给出推荐开发顺序，要求适合我分多次让 Cursor 实现。

请按照下面格式输出：

第 1 步：只新增数据库表、Entity、Mapper、Service 空壳；
第 2 步：实现知识库创建和查询；
第 3 步：实现文档上传、解析、切片；
第 4 步：实现 MemoryVectorStore 和 RagService；
第 5 步：实现 RAG 检索测试接口；
第 6 步：改造 ArticleState 和 Article 创建请求；
第 7 步：把 ragContext 接入标题、大纲、正文 Agent；
第 8 步：测试普通生成不受影响；
第 9 步：测试 RAG 生成流程。

九、请最后给出结论

请最终明确告诉我：
1. 当前项目是否建议使用 RAG；
2. 第一版是否必须上真实向量数据库；
3. 是否需要修改 SSE；
4. 是否需要修改配图模块；
5. 是否建议先用 MemoryVectorStore 跑通；
6. 下一步最适合让你生成哪一部分代码。
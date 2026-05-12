你现在是一个资深 Java Spring Boot + RAG 系统架构师，请先不要修改代码。

请完整阅读当前项目代码，重点分析以下模块：

1. 当前 RAG 流程：
    - RagService / RagServiceImpl
    - EmbeddingUtils
    - RetrievedChunk
    - buildRagContextByUserId
    - retrieveByUserId
    - 当前如何根据 query 和 chunk content 计算相似度
    - 当前如何拼接 ragContext

2. 文档上传和切片流程：
    - 知识库创建逻辑
    - 文档上传逻辑
    - 文档解析逻辑
    - course_document_chunk 表对应实体和 Service
    - chunk 是在哪里保存到数据库的
    - chunk content、kbId、documentId、userId 分别从哪里来

3. 当前数据库和技术栈：
    - 当前主业务库是否是 MySQL
    - 项目使用 MyBatis-Flex，不是 MyBatis-Plus
    - 当前是否已有 JdbcTemplate
    - 当前 application.yml 数据源配置
    - 当前 Spring AI / DashScope 依赖和配置

我的目标是：把当前 RAG 从“临时文本相似度检索”升级为“DashScope Embedding + PGVector 向量数据库检索”。

背景：
当前项目中的 EmbeddingUtils 仍然是关键词命中率 + 字符覆盖率的伪相似度计算。现在希望改造成标准 RAG 流程：

文档上传
↓
文档解析
↓
文档切割 chunk
↓
调用 DashScope Embedding 模型生成 chunk embedding
↓
将 chunk embedding 写入 PGVector
↓
用户 query 也调用 DashScope Embedding 生成 query embedding
↓
通过 PGVector cosine distance 查询 topK chunk
↓
返回 RetrievedChunk
↓
拼接 ragContext
↓
交给大模型生成正文

要求：
1. 第一版使用 PGVector 作为个人向量数据库；
2. 不使用 Milvus；
3. 不使用 Elasticsearch；
4. 不引入真实 reranker 模型；
5. 检索阶段先使用 PGVector 的余弦相似度 TopK 召回；
6. 尽量保持现有 RagService.retrieveByUserId(kbId, query, topK, userId) 接口不变；
7. 尽量保持 RagService.buildRagContextByUserId(kbId, query, topK, userId) 接口不变；
8. 不修改 SSE；
9. 不修改配图流程；
10. 不修改 MCP 主流程；
11. 项目使用 MyBatis-Flex，不要使用 MyBatis-Plus；
12. 如果 PGVector 检索异常，要降级为原来的 EmbeddingUtils 文本相似度检索；
13. 如果某些 chunk 没有 embedding，不要影响文章生成；
14. 文档切片入库和向量入库要尽量解耦；
15. 当前 Docker 中已经启动了 PGVector 容器，端口为 localhost:5432。

请先生成一份 Markdown 开发文档，不要直接修改代码。

开发文档请包括以下内容：

一、现有 RAG 流程分析
1. 当前文档上传、解析、切片、保存流程；
2. 当前 RagService.retrieveByUserId 的检索逻辑；
3. 当前 EmbeddingUtils 的局限；
4. 当前 buildRagContextByUserId 如何使用 RetrievedChunk；
5. 哪些接口需要保持不变。

二、PGVector 接入目标
1. 为什么选择 PGVector，而不是 Milvus 或 Elasticsearch；
2. PGVector 在本项目中负责什么；
3. MySQL 主业务库和 PGVector 向量库的关系；
4. 是否需要把主库迁移到 PostgreSQL；
5. 第一版如何做到最小侵入。

三、数据库设计
请设计 PGVector 中的新表：

course_document_chunk_embedding

字段建议包括：
- id
- chunk_id
- document_id
- kb_id
- user_id
- content
- embedding vector(维度)
- model_name
- create_time
- update_time

要求：
1. 保留原 course_document_chunk 表；
2. course_document_chunk 继续作为 chunk 元数据主表；
3. course_document_chunk_embedding 只负责保存向量和冗余 content；
4. chunk_id 要能关联回原 chunk；
5. user_id 和 kb_id 要用于权限隔离和过滤；
6. embedding 维度要根据 DashScope Embedding 模型确认；
7. 如果暂时不确定维度，请在文档中说明如何确认。

请给出 SQL，包括：
1. CREATE EXTENSION vector;
2. CREATE TABLE course_document_chunk_embedding;
3. user_id + kb_id 索引；
4. chunk_id 索引；
5. HNSW cosine 索引；
6. 可选 unique(chunk_id)。

四、后端数据源设计
当前项目主数据源可能是 MySQL，PGVector 是单独的 PostgreSQL 服务。

请设计：
1. application.yml 中 pgvector.datasource 配置；
2. PgVectorDataSourceConfig；
3. PgVectorJdbcConfig；
4. 使用 JdbcTemplate 操作 PGVector；
5. 不影响原 MyBatis-Flex 主数据源；
6. 不把整个项目主库切换为 PostgreSQL。

PGVector 连接信息参考：
host: localhost
port: 5432
database: rag_db
username: postgres
password: 123456

五、EmbeddingUtils 改造
请设计如何将 EmbeddingUtils 从关键词相似度升级为 DashScope Embedding。

要求：
1. 保留 calculateTextSimilarity(String query, String content) 作为 fallback；
2. 新增 embed(String text) 方法；
3. 使用 Spring AI 的 EmbeddingModel 或项目已有 DashScope embedding 调用方式；
4. 对文本长度做限制，例如 3000 字符；
5. 对 embedding 调用异常做 catch；
6. 可加入本地缓存，避免重复 embedding；
7. 不要让 embedding 调用失败影响主流程。

请给出代码骨架。

六、VectorStoreService 设计
请新增接口：

VectorStoreService

方法建议：

void upsertChunkEmbedding(Long userId,
String kbId,
Long documentId,
Long chunkId,
String content,
List<Double> embedding,
String modelName);

List<RetrievedChunk> search(Long userId,
String kbId,
List<Double> queryEmbedding,
Integer topK);

要求：
1. 第一版实现 PgVectorStoreServiceImpl；
2. 后续可扩展 MilvusVectorStoreServiceImpl 或 ElasticsearchVectorStoreServiceImpl；
3. RagService 不直接写 PGVector SQL；
4. 向量存储逻辑与 RAG 业务逻辑解耦。

七、PgVectorStoreServiceImpl 实现设计
请设计 PgVectorStoreServiceImpl。

要求：
1. 使用 pgVectorJdbcTemplate；
2. upsert 时可以先删后插，或者使用 ON CONFLICT；
3. embedding 用 PGVector 支持的字符串格式写入，例如 [0.1,0.2,0.3]；
4. search 时使用 cosine distance；
5. SQL 类似：
   SELECT chunk_id, content, 1 - (embedding <=> ?::vector) AS score
   FROM course_document_chunk_embedding
   WHERE user_id = ? AND kb_id = ?
   ORDER BY embedding <=> ?::vector
   LIMIT ?
6. 返回 RetrievedChunk；
7. topK 默认 5，最大限制 20；
8. 异常要记录日志并向上抛出或返回空列表，由 RagService 决定是否 fallback。

八、文档切片入库时如何生成 embedding
请分析当前项目中 chunk 是在哪里保存的。

请设计：
1. 在 chunk 保存成功后，调用 EmbeddingUtils.embed(chunk.getContent())；
2. 调用 VectorStoreService.upsertChunkEmbedding 保存到 PGVector；
3. 如果 embedding 生成失败，只记录日志，不影响文档上传；
4. 如果 PGVector 写入失败，只记录日志，不影响文档上传；
5. 后续可以增加异步补偿任务；
6. 第一版可以同步写入，但要说明可能带来上传耗时；
7. 如果项目已有异步线程池，可以建议使用异步写入。

九、RagService.retrieveByUserId 改造
请设计如何尽量保持接口不变：

原接口：
retrieveByUserId(String kbId, String query, Integer topK, Long userId)

新逻辑：
1. 校验参数；
2. 调用 EmbeddingUtils.embed(query) 生成 query embedding；
3. 调用 VectorStoreService.search(userId, kbId, queryEmbedding, topK)；
4. 如果 PGVector 返回结果不为空，直接返回；
5. 如果 PGVector 结果为空，可以返回空列表或 fallback；
6. 如果 PGVector 异常，降级到旧的关键词相似度检索；
7. 旧检索逻辑建议抽取为 retrieveByKeywordFallback；
8. buildRagContextByUserId 尽量不改。

十、历史数据补向量方案
项目已有的 course_document_chunk 可能没有 embedding。

请设计一个补偿方案：
1. 新增一个管理接口或脚本；
2. 扫描某个 kbId 下所有 chunk；
3. 对没有 embedding 的 chunk 生成 embedding；
4. 写入 PGVector；
5. 支持按 userId、kbId 执行；
6. 失败记录日志；
7. 第一版可以只做手动触发，不做定时任务。

十一、配置项设计
请设计 application.yml 配置：

pgvector:
datasource:
driver-class-name: org.postgresql.Driver
jdbc-url: jdbc:postgresql://localhost:5432/rag_db
username: postgres
password: 123456

rag:
vector:
enabled: true
top-k: 5
max-top-k: 20
model-name: text-embedding-v4
fallback-enabled: true

要求：
1. rag.vector.enabled=false 时，完全使用旧检索；
2. fallback-enabled=true 时，PGVector 异常降级旧检索；
3. model-name 用于保存到 course_document_chunk_embedding.model_name；
4. topK 有默认值。

十二、日志设计
请设计以下日志：
1. 文档切片 embedding 生成日志；
2. PGVector 写入日志；
3. query embedding 生成日志；
4. PGVector 检索日志；
5. TopK 召回结果日志；
6. fallback 降级日志；
7. 历史数据补向量日志。

日志中要包含：
- userId
- kbId
- documentId
- chunkId
- queryLength
- contentLength
- score
- elapsedMs
- reason

十三、测试方案
请给出测试用例：

测试 1：PGVector 连接测试
- 启动后端；
- 执行 SELECT version();
- 执行 SELECT extversion FROM pg_extension WHERE extname='vector';

测试 2：创建向量表
- 执行 pgvector_init.sql；
- 确认 vector extension 和 course_document_chunk_embedding 表存在。

测试 3：上传文档并切片
- 上传知识库文档；
- 确认 course_document_chunk 有数据；
- 确认 course_document_chunk_embedding 有对应数据。

测试 4：RAG 检索
- 输入 query；
- 确认 retrieveByUserId 返回 PGVector topK；
- score 合理；
- buildRagContextByUserId 正常拼接上下文。

测试 5：PGVector 异常降级
- 停止 pgvector 容器；
- 再次执行 RAG；
- 应降级到旧关键词相似度；
- 文章生成不失败。

测试 6：历史 chunk 补向量
- 对已有知识库执行补向量；
- 确认缺失 embedding 的 chunk 被补齐。

十四、本阶段不要做
请明确说明本阶段不做：
1. 不接 Milvus；
2. 不接 Elasticsearch；
3. 不引入真实 reranker 模型；
4. 不做混合检索；
5. 不做 MCP 改造；
6. 不改 SSE；
7. 不改配图流程；
8. 不迁移主业务库；
9. 不删除原 EmbeddingUtils 旧逻辑；
10. 不破坏原 ragEnabled / kbId / ragMode 流程。

十五、最终输出格式
请输出一份 Markdown 开发文档，文件名建议：

pgvector_rag_向量检索改造方案_v1.md

文档中必须包含：
1. 新增文件列表；
2. 修改文件列表；
3. 数据库 SQL；
4. application.yml 配置；
5. 核心代码骨架；
6. RAG 新旧流程对比；
7. fallback 降级逻辑；
8. 测试步骤；
9. 风险点和注意事项。

请注意：
本次只生成开发文档，不要直接修改代码。
# PGVector 向量检索改造方案 v1

> 将当前 RAG 从"关键词命中率 + 字符覆盖率伪相似度"升级为"DashScope Embedding + PGVector 向量数据库检索"
>
> 本次只输出开发文档，不修改代码。

---

## 一、现有 RAG 流程分析

### 1.1 当前文档上传、解析、切片、保存流程

```
Client (POST /api/course/document/upload)
  └─ CourseDocumentController.uploadDocument()
       └─ CourseDocumentServiceImpl.uploadDocument(kbId, file, loginUser)  [@Transactional]
            ├─ [1] 校验参数、文件大小(≤10MB)、文件类型(txt/md)
            ├─ [2] 校验知识库归属 (validateKbOwnership)
            ├─ [3] 创建 CourseDocument 记录 (status=PARSING) → MySQL course_document
            ├─ [4] DocumentParser.parse(file, fileType) → 读 UTF-8 纯文本
            ├─ [5] DocumentChunker.split(content) → 重叠切片 (chunkSize=600, overlap=100)
            ├─ [6] 创建 CourseDocumentChunk 实体列表 → courseDocumentChunkService.saveBatch()
            │      → MySQL course_document_chunk 表
            ├─ [7] 更新 CourseDocument (status=SUCCESS, chunkCount=N)
            └─ [8] 更新 CourseKnowledgeBase (documentCount++, chunkCount+=N)
```

**关键发现：**
- chunk 在 `CourseDocumentServiceImpl.uploadDocument()` 第 100-112 行批量保存
- 当前无 embedding 生成步骤，`course_document_chunk.embedding` 字段虽存在但从未被代码填充
- 全程在一个 `@Transactional` 中，同步执行

### 1.2 当前 RagService.retrieveByUserId 的检索逻辑

```java
// RagServiceImpl.retrieve() 核心逻辑：
1. 校验 kbId 归属当前用户
2. 查询该 kbId 下**全部** chunk: courseDocumentChunkService.list(eq("kbId", kbId))
3. 遍历每个 chunk，调用 embeddingUtils.calculateTextSimilarity(query, chunk.getContent())
4. score > 0 的保留，无人为过滤
5. 按 score 降序排序，取 topK（默认5，最大10）
6. 返回 List<RetrievedChunk>
```

**关键问题：**
- 每次检索加载全库 chunk 到内存，chunk 数量大时性能差
- 相似度计算为纯 Java 字符串匹配，无语义理解
- topK 硬编码最大 10（`MAX_TOP_K = 10`）

### 1.3 当前 EmbeddingUtils 的局限

| 维度 | 当前实现 | 问题 |
|---|---|---|
| 算法 | 关键词命中率(0.6) + 字符覆盖率(0.4) | 无语义理解，"苹果"和"Apple"相似度为0 |
| 分词 | 空格分词 + 逐中文字符 | 无法处理"人工智能"这类词组 |
| 精度 | 字符级匹配 | 同义词、近义词完全无法匹配 |
| 扩展性 | O(N) 遍历所有 chunk | 10万 chunk 时每次检索需遍历全部 |

### 1.4 当前 buildRagContextByUserId 如何使用 RetrievedChunk

```java
// RagServiceImpl.buildRagContext() 核心逻辑：
1. 调用 retrieve() 获取 topK chunk
2. 拼接为格式化文本：
   [资料1 | 相关度：0.85]
   <chunk content>
   ...
3. 总长度上限 MAX_CONTEXT_LENGTH = 4000 字符
4. 返回拼接后的字符串，或空字符串（异常/无结果时）
```

调用方 `ArticleAsyncService.enrichStateWithRagContext()` 在第 419-446 行：
- 检查 `article.ragEnabled == 1` 且 `kbId` 非空
- 调用 `ragService.buildRagContextByUserId(kbId, topic, null, userId)`
- 结果写入 `state.ragContext`
- 异常时降级为空上下文，不阻断文章生成

### 1.5 需保持不变的接口

| 接口 | 签名 | 调用方 |
|---|---|---|
| `RagService.retrieveByUserId` | `(String kbId, String query, Integer topK, Long userId)` | 内部/Controller |
| `RagService.buildRagContextByUserId` | `(String kbId, String query, Integer topK, Long userId)` | ArticleAsyncService |
| `RagService.retrieve` | `(String kbId, String query, Integer topK, User loginUser)` | RagSearchController |
| `RagService.buildRagContext` | `(String kbId, String query, Integer topK, User loginUser)` | 内部 |
| `EmbeddingUtils.calculateTextSimilarity` | `(String query, String content)` | RagServiceImpl(fallback) |
| `CourseDocumentServiceImpl.uploadDocument` | `(String kbId, MultipartFile, User)` | CourseDocumentController |

---

## 二、PGVector 接入目标

### 2.1 为什么选择 PGVector

| 对比维度 | PGVector | Milvus | Elasticsearch |
|---|---|---|---|
| 部署复杂度 | 低（已有 Docker 容器） | 高（独立服务） | 中（已有生态） |
| 与现有栈集成 | PostgreSQL JDBC 即可 | 需 SDK + 服务 | 需 ES 客户端 |
| 向量检索能力 | HNSW 索引，cosine/L2/内积 | 全功能 | 需 x-pack 或插件 |
| 运维成本 | 低（与 PG 一体） | 高 | 中 |
| 数据量级 | 百万级足够 | 亿级 | 亿级 |
| 题目要求 | 明确要求 PGVector | 明确禁用 | 明确禁用 |

**本项目第一版场景：** 知识库 chunk 量级在万~十万级别，PGVector HNSW 索引完全满足性能需求。

### 2.2 PGVector 在本项目中的职责

- **唯一的向量存储与检索引擎**
- 存储 chunk embedding 向量 + 冗余 content
- 提供 cosine distance (余弦距离) TopK 检索
- 不做业务数据主存储（chunk 元数据仍走 MySQL）

### 2.3 MySQL 主业务库与 PGVector 向量库的关系

```
┌─────────────────────────────────────────────────┐
│ MySQL (ai_passage_creator)                      │
│ ├─ course_knowledge_base   (知识库元数据)         │
│ ├─ course_document          (文档元数据)          │
│ ├─ course_document_chunk    (chunk 元数据+content) │ ← 主表，不变
│ └─ article                  (文章)               │
└─────────────────────────────────────────────────┘
         ↑ chunk_id 关联
┌─────────────────────────────────────────────────┐
│ PostgreSQL (rag_db) + PGVector extension        │
│ └─ course_document_chunk_embedding (向量+冗余)    │ ← 新增
│    - chunk_id → 关联 MySQL chunk                │
│    - embedding vector(1024)                     │
│    - user_id, kb_id → 权限过滤                  │
└─────────────────────────────────────────────────┘
```

### 2.4 是否需把主库迁移到 PostgreSQL

**不需要。** MySQL 继续保持为主业务库，PGVector 仅作为向量存储的附属数据库。

### 2.5 第一版如何做到最小侵入

1. **不改 MySQL 表结构**：`course_document_chunk.embedding` 字段不动，PGVector 新表独立
2. **不改 MyBatis-Flex 配置**：PGVector 使用独立 JdbcTemplate，互不干扰
3. **不改接口签名**：`RagService` 四个方法签名保持不变
4. **不改 Agent/SSE/配图/MCP 流程**：只替换检索实现
5. **保留旧逻辑**：`EmbeddingUtils.calculateTextSimilarity()` 作为 fallback

---

## 三、数据库设计

### 3.1 Embedding 维度确认

**DashScope text-embedding-v4 模型输出维度：1024**

验证方式：
1. 查阅 [DashScope Embedding 文档](https://help.aliyun.com/zh/model-studio/getting-started/models#c4f40a31d8d95)
2. 调用 `EmbeddingModel.embed("test")` 查看返回的 `List<Double>` 的 `size()`
3. Spring AI Alibaba DashScope 自动映射为 `List<Double>`

### 3.2 PGVector 初始化 SQL

```sql
-- ============================================
-- PGVector 初始化脚本：pgvector_init.sql
-- 执行方式：psql -h localhost -p 5432 -U postgres -d rag_db -f pgvector_init.sql
-- ============================================

-- 1. 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 创建 chunk embedding 表
CREATE TABLE IF NOT EXISTS course_document_chunk_embedding (
    id            BIGSERIAL       PRIMARY KEY,
    chunk_id      VARCHAR(64)     NOT NULL,
    document_id   VARCHAR(64)     NOT NULL,
    kb_id         VARCHAR(64)     NOT NULL,
    user_id       BIGINT          NOT NULL,
    content       TEXT            NOT NULL,
    embedding     vector(1024)    NOT NULL,
    model_name    VARCHAR(64)     NOT NULL DEFAULT 'text-embedding-v4',
    create_time   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. 复合索引：user_id + kb_id（权限过滤 + 知识库范围检索）
CREATE INDEX IF NOT EXISTS idx_embedding_user_kb
    ON course_document_chunk_embedding (user_id, kb_id);

-- 4. chunk_id 索引（用于 upsert 和关联查询）
CREATE INDEX IF NOT EXISTS idx_embedding_chunk_id
    ON course_document_chunk_embedding (chunk_id);

-- 5. chunk_id 唯一约束（一个 chunk 只对应一条 embedding 记录）
-- 支持 upsert 时使用 ON CONFLICT (chunk_id) DO UPDATE
CREATE UNIQUE INDEX IF NOT EXISTS uk_embedding_chunk_id
    ON course_document_chunk_embedding (chunk_id);

-- 6. HNSW 余弦相似度索引（向量检索核心）
-- 参数说明：
--   m = 16: 每个节点的最大连接数（推荐 16~64）
--   ef_construction = 64: 构建时的搜索深度（推荐 32~200）
CREATE INDEX IF NOT EXISTS idx_embedding_hnsw_cosine
    ON course_document_chunk_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

### 3.3 字段说明

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL | PG 自增主键 |
| `chunk_id` | VARCHAR(64) | 关联 `course_document_chunk.chunkId` |
| `document_id` | VARCHAR(64) | 冗余，便于按文档维度管理 |
| `kb_id` | VARCHAR(64) | 知识库维度过滤 |
| `user_id` | BIGINT | 用户权限隔离 |
| `content` | TEXT | 冗余 chunk 原文（检索返回时无需回表 MySQL） |
| `embedding` | vector(1024) | 1024 维向量 |
| `model_name` | VARCHAR(64) | 记录生成 embedding 的模型，便于后续模型升级 |
| `create_time` | TIMESTAMP | 创建时间 |
| `update_time` | TIMESTAMP | 更新时间 |

**设计要点：**
- `content` 冗余存储，PGVector 检索返回时直接携带文本，避免回查 MySQL
- `user_id` + `kb_id` 复合索引确保检索时权限隔离
- `chunk_id` 唯一索引支持 `ON CONFLICT DO UPDATE` 语义的 upsert

---

## 四、后端数据源设计

### 4.1 pom.xml 新增依赖

```xml
<!-- PostgreSQL JDBC 驱动 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Spring Boot JDBC (JdbcTemplate) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

> **注意：** `spring-boot-starter-jdbc` 可能已被 `spring-boot-starter-web` 传递引入。如已存在则无需重复添加。可在项目中搜索 `JdbcTemplate` 类确认。

### 4.2 application.yml 新增配置

```yaml
# PGVector 数据源配置（独立于 MySQL 主数据源）
pgvector:
  datasource:
    driver-class-name: org.postgresql.Driver
    jdbc-url: jdbc:postgresql://localhost:5432/rag_db
    username: postgres
    password: 123456
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

# RAG 向量检索配置
rag:
  vector:
    enabled: true               # 是否启用 PGVector 检索
    top-k: 5                    # 默认 topK
    max-top-k: 20               # topK 最大值
    model-name: text-embedding-v4
    fallback-enabled: true      # PGVector 异常时是否降级到关键词检索
    text-max-length: 3000       # embedding 文本最大长度
```

### 4.3 PgVectorDataSourceConfig

```java
// 文件路径: src/main/java/com/yupi/template/config/PgVectorDataSourceConfig.java

package com.yupi.template.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class PgVectorDataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "pgvector.datasource")
    public DataSource pgVectorDataSource() {
        return new HikariDataSource();
    }

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }
}
```

**设计要点：**
- Bean 名称 `pgVectorJdbcTemplate` 与主数据源的 `jdbcTemplate` 区分
- 使用 `@ConfigurationProperties` 自动绑定 HikariCP 配置
- 不影响 Spring Boot 自动配置的 MySQL 主数据源

### 4.4 双数据源隔离验证

| 组件 | 数据源 | 用途 |
|---|---|---|
| MyBatis-Flex Mapper | MySQL (`spring.datasource`) | 业务 CRUD |
| `pgVectorJdbcTemplate` | PostgreSQL (`pgvector.datasource`) | 向量读写 |
| 其他 `JdbcTemplate` | MySQL（如有） | 通用查询 |

两者通过不同的 Bean 名称完全隔离，互不干扰。

---

## 五、EmbeddingUtils 改造

### 5.1 改造目标

- 保留 `calculateTextSimilarity(String query, String content)` 作为 fallback
- 新增 `embed(String text)` 方法，调用 DashScope Embedding API
- 新增本地缓存避免重复 embedding
- 异常不向上抛出（降级处理）

### 5.2 代码骨架

```java
// 文件路径: src/main/java/com/yupi/template/rag/EmbeddingUtils.java
// 在原文件基础上改造

package com.yupi.template.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class EmbeddingUtils {

    @Resource
    private EmbeddingModel embeddingModel;  // Spring AI 自动注入 DashScope EmbeddingModel

    /** 本地缓存：text -> embedding，避免重复调用 API */
    private final Map<String, List<Double>> embeddingCache = new ConcurrentHashMap<>();

    /** 单次 embedding 最大文本长度 */
    private static final int MAX_TEXT_LENGTH = 3000;

    // ========== 旧方法：保留作为 fallback ==========

    /**
     * 计算 query 与 content 的文本相关度（关键词 + 字符覆盖率）
     * 保留不做任何修改，作为 PGVector 异常时的降级方案
     */
    public double calculateTextSimilarity(String query, String content) {
        // ... 原有逻辑完全不变 ...
    }

    // ... 原有私有方法 extractKeywords, calculateCharCoverage, isChinese 不变 ...

    // ========== 新方法 ==========

    /**
     * 调用 DashScope Embedding API 生成文本向量
     *
     * @param text 输入文本
     * @return embedding 向量（1024维），失败返回 null
     */
    public List<Double> embed(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        // 截断过长文本
        String truncated = text.length() > MAX_TEXT_LENGTH
                ? text.substring(0, MAX_TEXT_LENGTH) : text;

        // 检查缓存
        String cacheKey = truncated.trim();
        List<Double> cached = embeddingCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            long start = System.currentTimeMillis();
            List<Double> embedding = embeddingModel.embed(truncated);
            long elapsed = System.currentTimeMillis() - start;

            if (embedding != null && !embedding.isEmpty()) {
                embeddingCache.put(cacheKey, embedding);
                log.info("Embedding 生成成功, textLength={}, dim={}, elapsedMs={}",
                        truncated.length(), embedding.size(), elapsed);
                return embedding;
            }

            log.warn("Embedding 返回空, textLength={}, elapsedMs={}", truncated.length(), elapsed);
            return null;

        } catch (Exception e) {
            log.error("Embedding 生成失败, textLength={}, elapsedMs={}",
                    truncated.length(), System.currentTimeMillis(), e);
            return null;
        }
    }

    /**
     * 清空 embedding 缓存（模型切换时调用）
     */
    public void clearCache() {
        embeddingCache.clear();
        log.info("Embedding 缓存已清空");
    }
}
```

### 5.3 EmbeddingModel 自动注入说明

`spring-ai-alibaba-starter-dashscope` 已自动配置 `EmbeddingModel` Bean：
- 默认使用 `text-embedding-v4` 模型
- 在 `application.yml` 中已配置 `spring.ai.dashscope.api-key`
- 如需显式指定模型，可添加：
  ```yaml
  spring:
    ai:
      dashscope:
        embedding:
          options:
            model: text-embedding-v4
  ```

### 5.4 缓存策略

| 项目 | 选择 | 原因 |
|---|---|---|
| 缓存实现 | `ConcurrentHashMap` | 第一版简单可靠，无需引入 Caffeine/Redis |
| 缓存 key | `text.trim()` | 相同文本（含前后空格容忍）命中缓存 |
| 缓存淘汰 | 无（后续可加 LRU） | 第一版 chunk 数量有限，内存可承载 |
| 缓存失效 | `clearCache()` | 模型切换时手动调用 |

---

## 六、VectorStoreService 设计

### 6.1 接口定义

```java
// 文件路径: src/main/java/com/yupi/template/service/VectorStoreService.java

package com.yupi.template.service;

import com.yupi.template.rag.RetrievedChunk;

import java.util.List;

/**
 * 向量存储服务接口
 * 第一版实现：PgVectorStoreServiceImpl
 * 后续可扩展：MilvusVectorStoreServiceImpl / ElasticsearchVectorStoreServiceImpl
 */
public interface VectorStoreService {

    /**
     * 插入或更新 chunk 的 embedding 向量
     *
     * @param userId      用户ID（权限隔离）
     * @param kbId        知识库ID（检索过滤）
     * @param documentId  文档ID
     * @param chunkId     切片ID（唯一标识）
     * @param content     切片文本内容（冗余存储）
     * @param embedding   向量数据（1024维）
     * @param modelName   embedding 模型名称
     */
    void upsertChunkEmbedding(Long userId,
                              String kbId,
                              Long documentId,
                              String chunkId,
                              String content,
                              List<Double> embedding,
                              String modelName);

    /**
     * 向量相似度检索
     *
     * @param userId         用户ID（权限过滤）
     * @param kbId           知识库ID（检索范围）
     * @param queryEmbedding 查询向量
     * @param topK           返回数量
     * @return 相关切片列表（按相似度降序）
     */
    List<RetrievedChunk> search(Long userId,
                                String kbId,
                                List<Double> queryEmbedding,
                                Integer topK);
}
```

### 6.2 设计原则

- **接口与实现分离**：RagService 只依赖 `VectorStoreService` 接口，不直接写 PGVector SQL
- **可扩展**：后续只需新增实现类即可切换到其他向量数据库
- **异常处理**：实现类遇到异常记录日志并抛出任其传播，由 RagService 统一决定是否 fallback

---

## 七、PgVectorStoreServiceImpl 实现设计

### 7.1 实现类

```java
// 文件路径: src/main/java/com/yupi/template/service/impl/PgVectorStoreServiceImpl.java

package com.yupi.template.service.impl;

import com.yupi.template.rag.RetrievedChunk;
import com.yupi.template.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PgVectorStoreServiceImpl implements VectorStoreService {

    @Resource
    private JdbcTemplate pgVectorJdbcTemplate;  // PgVectorDataSourceConfig 中定义的 Bean

    /** topK 最大值限制 */
    private static final int MAX_TOP_K = 20;

    // ========== upsert ==========

    @Override
    public void upsertChunkEmbedding(Long userId, String kbId, Long documentId,
                                     String chunkId, String content,
                                     List<Double> embedding, String modelName) {
        try {
            String vectorStr = toVectorString(embedding);
            long start = System.currentTimeMillis();

            pgVectorJdbcTemplate.update("""
                INSERT INTO course_document_chunk_embedding
                    (chunk_id, document_id, kb_id, user_id, content, embedding, model_name, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?, ?)
                ON CONFLICT (chunk_id)
                DO UPDATE SET
                    content     = EXCLUDED.content,
                    embedding   = EXCLUDED.embedding,
                    model_name  = EXCLUDED.model_name,
                    update_time = EXCLUDED.update_time
                """,
                chunkId, documentId, kbId, userId, content,
                vectorStr, modelName,
                Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(LocalDateTime.now())
            );

            long elapsed = System.currentTimeMillis() - start;
            log.info("PGVector upsert 成功, chunkId={}, userId={}, kbId={}, dim={}, elapsedMs={}",
                    chunkId, userId, kbId, embedding.size(), elapsed);

        } catch (Exception e) {
            log.error("PGVector upsert 失败, chunkId={}, userId={}, kbId={}",
                    chunkId, userId, kbId, e);
            throw new RuntimeException("PGVector 写入失败", e);
        }
    }

    // ========== search ==========

    @Override
    public List<RetrievedChunk> search(Long userId, String kbId,
                                       List<Double> queryEmbedding, Integer topK) {
        int k = Math.min(topK != null && topK > 0 ? topK : 5, MAX_TOP_K);
        String vectorStr = toVectorString(queryEmbedding);

        try {
            long start = System.currentTimeMillis();

            List<RetrievedChunk> results = pgVectorJdbcTemplate.query(
                """
                SELECT chunk_id, document_id, kb_id, content,
                       1 - (embedding <=> ?::vector) AS score
                FROM course_document_chunk_embedding
                WHERE user_id = ? AND kb_id = ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """,
                (ResultSet rs, int rowNum) -> {
                    RetrievedChunk chunk = new RetrievedChunk();
                    chunk.setChunkId(rs.getString("chunk_id"));
                    chunk.setDocId(rs.getString("document_id"));
                    chunk.setKbId(rs.getString("kb_id"));
                    chunk.setContent(rs.getString("content"));
                    chunk.setScore(rs.getDouble("score"));
                    return chunk;
                },
                vectorStr, userId, kbId, vectorStr, k
            );

            long elapsed = System.currentTimeMillis() - start;
            log.info("PGVector 检索完成, userId={}, kbId={}, topK={}, resultCount={}, elapsedMs={}",
                    userId, kbId, k, results.size(), elapsed);

            if (log.isDebugEnabled() && !results.isEmpty()) {
                for (int i = 0; i < results.size(); i++) {
                    RetrievedChunk rc = results.get(i);
                    log.debug("  Top{}: chunkId={}, score={:.4f}, contentPreview={}",
                            i + 1, rc.getChunkId(), rc.getScore(),
                            rc.getContent().substring(0, Math.min(50, rc.getContent().length())));
                }
            }

            return results;

        } catch (Exception e) {
            log.error("PGVector 检索异常, userId={}, kbId={}, topK={}", userId, kbId, k, e);
            throw new RuntimeException("PGVector 检索失败", e);
        }
    }

    // ========== 工具方法 ==========

    /**
     * 将 List<Double> 转为 PGVector 兼容的字符串格式：[0.1,0.2,0.3]
     */
    private String toVectorString(List<Double> embedding) {
        return "[" + embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")) + "]";
    }
}
```

### 7.2 SQL 说明

**upsert SQL:**
```sql
INSERT INTO course_document_chunk_embedding (...) VALUES (...)
ON CONFLICT (chunk_id) DO UPDATE SET ...
```
- `chunk_id` 有唯一索引 `uk_embedding_chunk_id`
- 首次插入时创建记录
- 同一 chunk 重新生成 embedding 时更新（如模型升级后重新 embed）

**search SQL:**
```sql
SELECT chunk_id, document_id, kb_id, content,
       1 - (embedding <=> ?::vector) AS score
FROM course_document_chunk_embedding
WHERE user_id = ? AND kb_id = ?
ORDER BY embedding <=> ?::vector
LIMIT ?
```
- `<=>` 是 PGVector 的 cosine distance 操作符（值域 [0, 2]）
- `1 - cosine_distance` 转换为余弦相似度（值域 [-1, 1]，越大越相似）
- `user_id` + `kb_id` 双重过滤确保权限隔离
- 使用 HNSW 索引（`idx_embedding_hnsw_cosine`）加速检索

### 7.3 异常处理约定

| 场景 | PgVectorStoreServiceImpl 行为 | RagService 行为 |
|---|---|---|
| upsert 失败 | 记录日志 + 抛出 RuntimeException | 捕获并记录日志，不影响文档上传 |
| search 异常 | 记录日志 + 抛出 RuntimeException | 捕获并 fallback 到关键词检索 |
| search 返回空 | 返回 `List.of()` | 可选 fallback 到关键词检索 |

---

## 八、文档切片入库时生成 Embedding

### 8.1 改动位置

**文件：** `CourseDocumentServiceImpl.uploadDocument()`（第 63-136 行）

**改动点：** 在第 112 行 `courseDocumentChunkService.saveBatch(chunkEntities)` 之后，新增 embedding 生成和 PGVector 写入逻辑。

### 8.2 改造方案

```java
// CourseDocumentServiceImpl.java 改造（伪代码骨架）

@Resource
private EmbeddingUtils embeddingUtils;

@Resource
private VectorStoreService vectorStoreService;

// 在 uploadDocument() 方法中，saveBatch 之后新增：

// 8.5 为切片生成 embedding 并写入 PGVector（异步解耦，不影响文档上传）
for (CourseDocumentChunk chunk : chunkEntities) {
    try {
        List<Double> embedding = embeddingUtils.embed(chunk.getContent());
        if (embedding != null && !embedding.isEmpty()) {
            vectorStoreService.upsertChunkEmbedding(
                loginUser.getId(),
                kbId,
                docId,           // 注意：docId 是 String，实体用 String 即可
                chunk.getChunkId(),
                chunk.getContent(),
                embedding,
                "text-embedding-v4"
            );
        } else {
            log.warn("Chunk embedding 生成返回空, chunkId={}, docId={}",
                    chunk.getChunkId(), docId);
        }
    } catch (Exception e) {
        // embedding 生成或 PGVector 写入失败，只记录日志，不影响文档上传
        log.error("Chunk embedding 写入 PGVector 失败, chunkId={}, docId={}",
                chunk.getChunkId(), docId, e);
    }
}
```

### 8.3 同步 vs 异步

| 方案 | 优点 | 缺点 | 建议 |
|---|---|---|---|
| 同步写入 | 简单，事务性好理解 | 文档上传耗时增加（每个 chunk 调一次 API） | 第一版可用 |
| 异步写入 | 上传快，不阻塞用户 | 需要线程池，短暂不一致 | 推荐后续优化 |

**第一版建议：同步写入。** 理由：
1. 典型文档切片数 20~50 个，即使同步，embedding API 调用在可接受范围
2. 简单可靠，不需要引入异步复杂度
3. 在日志中记录耗时，为后续异步优化提供数据

如果项目已有线程池（如 `articleExecutor`），可直接复用：
```java
@Resource
private ThreadPoolTaskExecutor articleExecutor;  // 或 @Async 专用线程池

// 异步写入示例（可选，第二版）
articleExecutor.execute(() -> {
    // ... embedding 生成 + PGVector 写入 ...
});
```

---

## 九、RagService.retrieveByUserId 改造

### 9.1 改造目标

- **接口签名不变**：`retrieveByUserId(String kbId, String query, Integer topK, Long userId)`
- **检索优先级**：PGVector 向量检索 → 空结果或异常 → fallback 关键词检索
- **旧逻辑保留**：抽取为 `retrieveByKeywordFallback` 私有方法

### 9.2 RagServiceImpl 改造代码骨架

```java
// RagServiceImpl.java 改造（在原有基础上新增，不删除旧代码）

@Resource
private EmbeddingUtils embeddingUtils;

@Resource
private VectorStoreService vectorStoreService;

// 新增：向量检索开关（从配置读取）
@Value("${rag.vector.enabled:true}")
private boolean vectorEnabled;

@Value("${rag.vector.fallback-enabled:true}")
private boolean fallbackEnabled;

/** 新的 topK 最大值（PGVector 能力更强，放大到 20） */
private static final int MAX_TOP_K_VECTOR = 20;

@Override
public List<RetrievedChunk> retrieveByUserId(String kbId, String query,
                                              Integer topK, Long userId) {
    User user = new User();
    user.setId(userId);
    return retrieve(kbId, query, topK, user);
}

// ========== 核心改造：retrieve() ==========

@Override
public List<RetrievedChunk> retrieve(String kbId, String query,
                                      Integer topK, User loginUser) {
    // 1. 参数校验（不变）
    ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
    ThrowUtils.throwIf(kbId == null || kbId.trim().isEmpty(),
            ErrorCode.PARAMS_ERROR, "知识库ID不能为空");
    ThrowUtils.throwIf(query == null || query.trim().isEmpty(),
            ErrorCode.PARAMS_ERROR, "检索内容不能为空");

    // 2. topK 处理
    int maxK = vectorEnabled ? MAX_TOP_K_VECTOR : MAX_TOP_K;
    if (topK == null || topK <= 0) {
        topK = DEFAULT_TOP_K;
    }
    topK = Math.min(topK, maxK);

    // 3. 校验知识库归属（不变）
    QueryWrapper kbQuery = QueryWrapper.create().eq("kbId", kbId);
    CourseKnowledgeBase kb = courseKnowledgeBaseService.getOne(kbQuery);
    ThrowUtils.throwIf(kb == null, ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
    ThrowUtils.throwIf(!kb.getUserId().equals(loginUser.getId()),
            ErrorCode.NO_AUTH_ERROR, "无权检索该知识库");

    // ====== 新增：PGVector 向量检索 ======
    if (vectorEnabled) {
        try {
            List<Double> queryEmbedding = embeddingUtils.embed(query);
            if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
                List<RetrievedChunk> vectorResults =
                        vectorStoreService.search(loginUser.getId(), kbId,
                                queryEmbedding, topK);

                if (vectorResults != null && !vectorResults.isEmpty()) {
                    log.info("PGVector 检索命中, kbId={}, query={}, topK={}, resultCount={}",
                            kbId, queryPreview(query), topK, vectorResults.size());
                    return vectorResults;
                }

                // 向量检索返回空（可能 chunk 没有 embedding）
                log.info("PGVector 检索返回空, kbId={}, query={}, 将使用 fallback",
                        kbId, queryPreview(query));
            } else {
                log.warn("Query embedding 生成返回 null, kbId={}, query={}",
                        kbId, queryPreview(query));
            }
        } catch (Exception e) {
            log.warn("PGVector 检索异常, kbId={}, query={}, 降级到关键词检索",
                    kbId, queryPreview(query), e);
        }

        // fallback 已禁用时，直接返回空
        if (!fallbackEnabled) {
            return List.of();
        }
    }

    // ====== Fallback: 旧关键词相似度检索 ======
    log.info("使用关键词 fallback 检索, kbId={}, query={}, topK={}",
            kbId, queryPreview(query), topK);
    return retrieveByKeywordFallback(kbId, query, topK);
}

// ========== 新增：关键词 fallback 检索 ==========

/**
 * 基于关键词相似度的检索（旧逻辑）
 * 仅在 PGVector 不可用时作为降级方案
 */
private List<RetrievedChunk> retrieveByKeywordFallback(String kbId, String query,
                                                        Integer topK) {
    // 加载该知识库下所有切片
    QueryWrapper chunkQuery = QueryWrapper.create().eq("kbId", kbId);
    List<CourseDocumentChunk> allChunks = courseDocumentChunkService.list(chunkQuery);

    if (allChunks == null || allChunks.isEmpty()) {
        return List.of();
    }

    // 计算关键词相似度
    List<RetrievedChunk> scoredChunks = new ArrayList<>();
    for (CourseDocumentChunk chunk : allChunks) {
        double score = embeddingUtils.calculateTextSimilarity(query, chunk.getContent());
        if (score > 0 || allChunks.size() <= topK) {
            RetrievedChunk rc = new RetrievedChunk();
            rc.setId(chunk.getId());
            rc.setChunkId(chunk.getChunkId());
            rc.setDocId(chunk.getDocId());
            rc.setKbId(chunk.getKbId());
            rc.setChunkIndex(chunk.getChunkIndex());
            rc.setContent(chunk.getContent());
            rc.setScore(score);
            scoredChunks.add(rc);
        }
    }

    return scoredChunks.stream()
            .sorted(Comparator.comparingDouble(RetrievedChunk::getScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
}

// ========== 辅助方法 ==========

private String queryPreview(String query) {
    return query.substring(0, Math.min(50, query.length()));
}
```

### 9.3 buildRagContext / buildRagContextByUserId

**不需要修改。** 这两个方法只依赖 `retrieve()` 的返回值（`List<RetrievedChunk>`），而 `retrieve()` 的内部实现已改造。调用方无感知。

### 9.4 降级逻辑流程

```
retrieve(kbId, query, topK, user)
  │
  ├─ vectorEnabled == false?
  │   └─ YES → 直接进入 fallback 关键词检索
  │
  ├─ 调用 embeddingUtils.embed(query)
  │   └─ 返回 null? → 记录 warn 日志 → 进入 fallback
  │
  ├─ 调用 vectorStoreService.search(userId, kbId, embedding, topK)
  │   ├─ 抛出异常? → 记录 warn 日志（含 reason）→ 进入 fallback
  │   └─ 返回空列表? → 进入 fallback
  │
  ├─ 返回非空结果? → 直接返回 PGVector 结果
  │
  └─ fallbackEnabled == false? → 返回空列表
       └─ fallbackEnabled == true? → retrieveByKeywordFallback(kbId, query, topK)
```

---

## 十、历史数据补向量方案

### 10.1 背景

项目中已存在的 `course_document_chunk` 没有对应的 embedding 记录。需要提供补偿机制让存量 chunk 也能被 PGVector 检索到。

### 10.2 管理接口设计

```java
// 文件路径: src/main/java/com/yupi/template/controller/VectorAdminController.java

@RestController
@RequestMapping("/api/admin/vector")
@Slf4j
public class VectorAdminController {

    @Resource
    private CourseDocumentChunkService courseDocumentChunkService;

    @Resource
    private EmbeddingUtils embeddingUtils;

    @Resource
    private VectorStoreService vectorStoreService;

    /**
     * 对指定知识库的未向量化 chunk 进行补向量
     *
     * POST /api/admin/vector/backfill
     * Body: { "kbId": "xxx", "userId": 1 }
     */
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestBody Map<String, Object> params) {
        String kbId = (String) params.get("kbId");
        Long userId = params.get("userId") != null
                ? ((Number) params.get("userId")).longValue() : null;

        // 1. 查询需要补向量的 chunk
        // 逻辑：查询 course_document_chunk 中存在但 course_document_chunk_embedding 中不存在的 chunk
        // 第一版简化：查询该 kbId 下所有 chunk，逐个尝试生成 embedding
        // （upsert 语义天然幂等，已有 embedding 的 chunk 会被更新）

        QueryWrapper query = QueryWrapper.create().eq("kbId", kbId);
        if (userId != null) {
            // 注意：course_document_chunk 表没有 userId，需要关联查询
            // 第一版简化：通过 kbId 控制即可
        }
        List<CourseDocumentChunk> chunks = courseDocumentChunkService.list(query);

        int success = 0;
        int fail = 0;

        for (CourseDocumentChunk chunk : chunks) {
            try {
                List<Double> embedding = embeddingUtils.embed(chunk.getContent());
                if (embedding != null && !embedding.isEmpty()) {
                    vectorStoreService.upsertChunkEmbedding(
                        userId != null ? userId : 0L,  // fallback userId
                        chunk.getKbId(),
                        chunk.getDocId(),
                        chunk.getChunkId(),
                        chunk.getContent(),
                        embedding,
                        "text-embedding-v4"
                    );
                    success++;
                } else {
                    fail++;
                }
            } catch (Exception e) {
                log.error("补向量失败, chunkId={}", chunk.getChunkId(), e);
                fail++;
            }
        }

        log.info("历史数据补向量完成, kbId={}, total={}, success={}, fail={}",
                kbId, chunks.size(), success, fail);

        return Map.of("total", chunks.size(), "success", success, "fail", fail);
    }
}
```

### 10.3 执行方式

| 方式 | 说明 |
|---|---|
| 手动调用 API | `POST /api/admin/vector/backfill` |
| SQL 查询缺失 | 后续可加 `GET /api/admin/vector/missing?kbId=xxx` 查询缺失 embedding 的 chunk 数量 |
| 定时任务 | 第一版不做，后续可加 `@Scheduled` 自动扫描 |

### 10.4 注意事项

- upsert 语义天然幂等，重复执行不会产生重复数据
- 每次 API 调用会重新生成 embedding 并覆盖已有记录（模型升级后可用）
- 大量 chunk 时 API 可能超时，后续可改为异步 + 进度查询

---

## 十一、配置项设计

### 11.1 完整 application.yml 配置

```yaml
# ============================================
# PGVector 向量检索配置（新增）
# ============================================

# PGVector 数据源
pgvector:
  datasource:
    driver-class-name: org.postgresql.Driver
    jdbc-url: jdbc:postgresql://localhost:5432/rag_db
    username: postgres
    password: 123456
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

# RAG 向量检索
rag:
  vector:
    enabled: true               # 是否启用 PGVector 向量检索
    top-k: 5                    # 默认返回条数
    max-top-k: 20               # topK 上限
    model-name: text-embedding-v4
    fallback-enabled: true      # PGVector 异常时降级到关键词检索
    text-max-length: 3000       # embedding 单次最大文本长度
```

### 11.2 配置项说明

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `rag.vector.enabled` | `true` | `false` 时完全使用旧关键词检索 |
| `rag.vector.top-k` | `5` | 默认 topK，用户可传参数覆盖 |
| `rag.vector.max-top-k` | `20` | topK 上限，PGVector 能力强于旧逻辑 |
| `rag.vector.model-name` | `text-embedding-v4` | 保存到 DB 的 `model_name` 字段 |
| `rag.vector.fallback-enabled` | `true` | PGVector 异常时是否启用旧检索降级 |
| `rag.vector.text-max-length` | `3000` | embedding 文本截断长度 |

### 11.3 环境切换

- **开发环境**：`pgvector.datasource.jdbc-url: jdbc:postgresql://localhost:5432/rag_db`
- **生产环境**：通过环境变量覆盖：
  ```yaml
  pgvector:
    datasource:
      jdbc-url: ${PGVECTOR_URL:jdbc:postgresql://localhost:5432/rag_db}
      username: ${PGVECTOR_USER:postgres}
      password: ${PGVECTOR_PASSWORD:123456}
  ```

---

## 十二、日志设计

### 12.1 日志规范

所有日志使用 SLF4J + Lombok `@Slf4j`，日志格式统一包含关键上下文字段。

### 12.2 各环节日志

#### (1) 文档切片 embedding 生成日志

```java
log.info("Chunk embedding 生成, chunkId={}, docId={}, kbId={}, userId={}, contentLength={}, elapsedMs={}",
        chunkId, docId, kbId, userId, content.length(), elapsedMs);
log.warn("Chunk embedding 生成返回空, chunkId={}, contentLength={}", chunkId, content.length());
log.error("Chunk embedding 生成失败, chunkId={}, contentLength={}", chunkId, content.length(), e);
```

#### (2) PGVector 写入日志

```java
log.info("PGVector upsert 成功, chunkId={}, userId={}, kbId={}, dim={}, elapsedMs={}",
        chunkId, userId, kbId, dim, elapsedMs);
log.error("PGVector upsert 失败, chunkId={}, userId={}, kbId={}, reason={}",
        chunkId, userId, kbId, e.getMessage(), e);
```

#### (3) query embedding 生成日志

```java
log.info("Query embedding 生成成功, queryLength={}, dim={}, elapsedMs={}",
        query.length(), embedding.size(), elapsedMs);
log.warn("Query embedding 生成返回 null, queryLength={}", query.length());
log.error("Query embedding 生成失败, queryLength={}", query.length(), e);
```

#### (4) PGVector 检索日志

```java
log.info("PGVector 检索, userId={}, kbId={}, topK={}, dim={}, elapsedMs={}",
        userId, kbId, topK, queryEmbedding.size(), elapsedMs);
log.info("PGVector 检索完成, resultCount={}, topScores=[{}]",
        results.size(), topScores);
log.error("PGVector 检索异常, userId={}, kbId={}, reason={}",
        userId, kbId, e.getMessage(), e);
```

#### (5) TopK 召回结果日志（debug 级别）

```java
log.debug("Top{}: chunkId={}, score={:.4f}, contentPreview={}",
        rank, rc.getChunkId(), rc.getScore(), contentPreview);
```

#### (6) fallback 降级日志

```java
log.warn("RAG 降级到关键词检索, kbId={}, query={}, reason={}, elapsedMs={}",
        kbId, queryPreview, reason, elapsedMs);
// reason 取值: "PGVECTOR_SEARCH_FAILED" | "PGVECTOR_EMPTY_RESULT" | "EMBEDDING_FAILED" | "VECTOR_DISABLED"
```

#### (7) 历史数据补向量日志

```java
log.info("补向量开始, kbId={}, totalChunks={}", kbId, totalChunks);
log.info("补向量进度, kbId={}, processed={}/{}, success={}, fail={}",
        kbId, processed, total, success, fail);
log.error("补向量单条失败, chunkId={}, reason={}", chunkId, e.getMessage());
log.info("补向量完成, kbId={}, total={}, success={}, fail={}, elapsedMs={}",
        kbId, total, success, fail, elapsedMs);
```

---

## 十三、测试方案

### 13.1 测试 1：PGVector 连接测试

**目的：** 确认后端能连接 PGVector 容器

**步骤：**
1. 启动后端应用
2. 在启动日志中确认 `HikariPool-2 - Starting...` for pgvector datasource
3. 通过临时 REST 接口或日志确认连接成功

**验证 SQL（可在 PostgreSQL 客户端执行）：**
```sql
SELECT version();                                          -- PostgreSQL 版本
SELECT extversion FROM pg_extension WHERE extname='vector'; -- pgvector 扩展版本
```

### 13.2 测试 2：创建向量表

**步骤：**
1. 执行 `pgvector_init.sql`
2. 确认输出无错误

**验证 SQL：**
```sql
SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';
SELECT table_name FROM information_schema.tables WHERE table_name = 'course_document_chunk_embedding';
SELECT indexname FROM pg_indexes WHERE tablename = 'course_document_chunk_embedding';
```

### 13.3 测试 3：上传文档并切片

**步骤：**
1. 创建知识库 `POST /api/course/kb/create`
2. 上传一个 .txt 测试文件 `POST /api/course/document/upload`
3. 检查 MySQL `course_document_chunk` 有新增数据
4. 检查 PG `course_document_chunk_embedding` 有对应数据

**验证：**
```sql
-- MySQL
SELECT COUNT(*) FROM course_document_chunk WHERE kbId = '<kbId>';
-- PGVector
SELECT COUNT(*) FROM course_document_chunk_embedding WHERE kb_id = '<kbId>';
-- 两表数量应一致
```

### 13.4 测试 4：RAG 检索

**步骤：**
1. 调用 `POST /api/rag/search` 传入 kbId 和相关 query
2. 检查返回的 `RetrievedChunkVO` 列表
3. 确认 score 合理（余弦相似度：0.3~1.0）
4. 确认 buildRagContext 正常拼接上下文
5. 创建一篇文章（ragEnabled=true, kbId=<kbId>），确认文章生成成功且引用了知识库内容

**验证：**
- 日志中出现 `PGVector 检索命中`
- score 值在合理范围（与 query 语义相关的 chunk 应有较高分数）
- 文章生成不报错

### 13.5 测试 5：PGVector 异常降级

**步骤：**
1. 停止 PGVector 容器：`docker stop <pgvector-container>`
2. 再次执行 RAG 检索 / 文章生成
3. 检查日志中出现 `降级到关键词检索`
4. 确认文章生成仍然成功（使用旧关键词相似度）

**恢复后测试：**
1. 启动 PGVector 容器：`docker start <pgvector-container>`
2. 再次执行 RAG 检索
3. 确认自动恢复为 PGVector 检索（日志中出现 `PGVector 检索命中`）

### 13.6 测试 6：历史 chunk 补向量

**步骤：**
1. 在 PGVector 容器停止时上传一批文档（chunk 只有 MySQL 记录，无 embedding）
2. 启动 PGVector 容器
3. 调用 `POST /api/admin/vector/backfill` 传入 kbId
4. 检查 PG `course_document_chunk_embedding` 补齐了缺失的 embedding
5. 执行 RAG 检索，确认能检索到补齐后的 chunk

### 13.7 测试 7：开关控制

**步骤：**
1. 设置 `rag.vector.enabled: false`
2. 执行 RAG 检索
3. 确认使用了旧关键词检索（日志无 PGVector 相关内容）
4. 恢复 `rag.vector.enabled: true`

---

## 十四、本阶段不做

| 事项 | 原因 |
|---|---|
| 接入 Milvus | 题目明确禁用 |
| 接入 Elasticsearch | 题目明确禁用 |
| 引入 reranker 模型 | 题目明确禁用 |
| 混合检索（向量+关键词） | 第一版简化，仅向量检索 |
| MCP 改造 | 不改 MCP 主流程 |
| SSE 改造 | 不改 SSE |
| 配图流程改造 | 不改配图流程 |
| 主业务库迁移到 PostgreSQL | MySQL 继续保持为主库 |
| 删除 EmbeddingUtils 旧逻辑 | 保留作为 fallback |
| 破坏 ragEnabled / kbId / ragMode 流程 | 完全不改 |
| 异步 embedding 写入 | 第一版同步，后续可优化 |
| 定时补向量任务 | 第一版仅手动触发 |

---

## 十五、文件清单与新旧流程对比

### 15.1 新增文件

| 文件 | 说明 |
|---|---|
| `sql/pgvector_init.sql` | PGVector 初始化脚本（建扩展、建表、建索引） |
| `src/main/java/com/yupi/template/config/PgVectorDataSourceConfig.java` | PGVector 数据源配置 |
| `src/main/java/com/yupi/template/service/VectorStoreService.java` | 向量存储服务接口 |
| `src/main/java/com/yupi/template/service/impl/PgVectorStoreServiceImpl.java` | PGVector 向量存储实现 |
| `src/main/java/com/yupi/template/controller/VectorAdminController.java` | 向量管理接口（补向量等） |

### 15.2 修改文件

| 文件 | 修改内容 |
|---|---|
| `pom.xml` | 新增 `postgresql` JDBC 驱动 + `spring-boot-starter-jdbc` 依赖（如未引入） |
| `src/main/resources/application.yml` | 新增 `pgvector.datasource` + `rag.vector` 配置块 |
| `src/main/java/com/yupi/template/rag/EmbeddingUtils.java` | 新增 `embed()` 方法 + `EmbeddingModel` 注入 + 本地缓存 |
| `src/main/java/com/yupi/template/service/impl/RagServiceImpl.java` | 改造 `retrieve()` 方法：PGVector 检索 + fallback 降级 |
| `src/main/java/com/yupi/template/service/impl/CourseDocumentServiceImpl.java` | 在 `saveBatch` 后新增 embedding 生成和 PGVector 写入 |

### 15.3 不变文件

| 文件 | 说明 |
|---|---|
| `RagService.java` | 接口签名完全不变 |
| `ArticleAsyncService.java` | 不修改，RagService 接口不变即可 |
| `PromptConstant.java` | 不修改 |
| `DocumentChunker.java` | 不修改 |
| `DocumentParser.java` | 不修改 |
| `RetrievedChunk.java` | 不修改 |
| `sql/rag_init.sql` | MySQL 表结构不变 |
| 所有 Agent 文件 | 不修改 |
| 所有 Controller（除新增外） | 不修改 |

### 15.4 新旧流程对比

```
【旧流程】                              【新流程】
query                                  query
  │                                      │
  ▼                                      ▼
RagServiceImpl.retrieve()              RagServiceImpl.retrieve()
  │                                      │
  ▼                                      ├─ [新增] embeddingUtils.embed(query)
加载全库 chunk 到内存                      │    → DashScope API → List<Double>
  │                                      │
  ▼                                      ├─ [新增] vectorStoreService.search()
逐条 calculateTextSimilarity()           │    → PGVector HNSW cosine TopK
  │                                      │
  ▼                                      ├─ 有结果? → 返回 RetrievedChunk
按 score 排序取 topK                       │
  │                                      ├─ 异常/空? → [保留] retrieveByKeywordFallback()
  ▼                                      │    → calculateTextSimilarity() 遍历 chunk
返回 RetrievedChunk                       │
                                         ▼
                                      返回 RetrievedChunk

【无变化】buildRagContext → 拼接 Prompt → 交给 Agent
```

### 15.5 风险点和注意事项

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| PGVector 容器不可用 | RAG 检索降级，文章质量可能下降 | fallback 机制 + 告警日志 |
| DashScope embedding API 限流 | embedding 生成失败，chunk 无向量 | 本地缓存 + 重试 + 日志告警 |
| embedding 文本过长 | API 调用失败或效果差 | 截断 3000 字符 |
| PGVector 数据与 MySQL 不一致 | 检索结果与实际 chunk 不同步 | 后续可加一致性校验接口 |
| 双数据源事务问题 | MySQL 回滚但 PGVector 已写入 | 目前 PGVector 写入在 try-catch 内，不影响 MySQL 事务 |
| HNSW 索引构建耗时 | 大量数据导入时写性能下降 | 批量导入时可临时删索引，导完重建 |
| chunk content 冗余存储 | PGVector 存储空间增大 | content 已压缩为 chunk（600字符），存储可接受 |

---

## 附录：开发顺序建议

按优先级从高到低：

| 阶段 | 步骤 | 优先级 |
|---|---|---|
| **Phase 1: 基础设施** | 1. 执行 `pgvector_init.sql` | P0 |
| | 2. `pom.xml` 加 PostgreSQL JDBC 驱动 | P0 |
| | 3. `application.yml` 加 PGVector 数据源配置 | P0 |
| | 4. 创建 `PgVectorDataSourceConfig.java` | P0 |
| **Phase 2: 向量服务** | 5. `EmbeddingUtils` 改造（新增 `embed()` 方法） | P0 |
| | 6. 创建 `VectorStoreService` 接口 | P0 |
| | 7. 创建 `PgVectorStoreServiceImpl` | P0 |
| **Phase 3: 接入流程** | 8. `CourseDocumentServiceImpl` 新增 embedding 写入 | P0 |
| | 9. `RagServiceImpl` 改造 `retrieve()` | P0 |
| **Phase 4: 补偿与测试** | 10. 创建 `VectorAdminController` 补向量接口 | P1 |
| | 11. 执行全部测试用例 | P0 |
| **Phase 5: 优化（后续）** | 12. embedding 异步写入 | P2 |

---

> **文档版本：** v1.0
> **编写日期：** 2026-05-08
> **适用分支：** main

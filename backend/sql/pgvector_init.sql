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
CREATE UNIQUE INDEX IF NOT EXISTS uk_embedding_chunk_id
    ON course_document_chunk_embedding (chunk_id);

-- 6. HNSW 余弦相似度索引（向量检索核心）
CREATE INDEX IF NOT EXISTS idx_embedding_hnsw_cosine
    ON course_document_chunk_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
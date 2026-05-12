package com.yupi.template.service.impl;

import com.yupi.template.rag.RetrievedChunk;
import com.yupi.template.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PgVectorStoreServiceImpl implements VectorStoreService {

    @Resource
    private JdbcTemplate pgVectorJdbcTemplate;

    private static final int MAX_TOP_K = 20;

    @Override
    public void upsertChunkEmbedding(Long userId, String kbId, String documentId,
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
                    log.debug("  Top{}: chunkId={}, score={}, contentPreview={}",
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

    /**
     * 将 List<Double> 转为 PGVector 兼容的字符串格式：[0.1,0.2,0.3]
     */
    private String toVectorString(List<Double> embedding) {
        return "[" + embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")) + "]";
    }
}

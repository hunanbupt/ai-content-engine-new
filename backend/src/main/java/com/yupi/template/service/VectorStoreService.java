package com.yupi.template.service;

import com.yupi.template.rag.RetrievedChunk;

import java.util.List;

/**
 * 向量存储服务接口
 * 第一版实现：PgVectorStoreServiceImpl
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
                              String documentId,
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

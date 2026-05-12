package com.yupi.template.controller;

import cn.hutool.core.util.IdUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.yupi.template.model.entity.CourseDocument;
import com.yupi.template.model.entity.CourseDocumentChunk;
import com.yupi.template.model.entity.CourseKnowledgeBase;
import com.yupi.template.rag.DocumentChunker;
import com.yupi.template.rag.DocumentParser;
import com.yupi.template.rag.EmbeddingUtils;
import com.yupi.template.service.CourseDocumentChunkService;
import com.yupi.template.service.CourseDocumentService;
import com.yupi.template.service.CourseKnowledgeBaseService;
import com.yupi.template.service.VectorStoreService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量管理接口（历史数据补向量等）
 *
 * @author <a href="https://codefather.cn">编程导航学习圈</a>
 */
@RestController
@RequestMapping("/api/admin/vector")
@Slf4j
public class VectorAdminController {

    @Resource
    private CourseDocumentChunkService courseDocumentChunkService;

    @Resource
    private CourseDocumentService courseDocumentService;

    @Resource
    private CourseKnowledgeBaseService courseKnowledgeBaseService;

    @Resource
    private DocumentParser documentParser;

    @Resource
    private DocumentChunker documentChunker;

    @Resource
    private EmbeddingUtils embeddingUtils;

    @Resource
    private VectorStoreService vectorStoreService;

    /**
     * 对指定知识库的未向量化 chunk 进行补向量
     */
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestBody Map<String, Object> params) {
        String kbId = (String) params.get("kbId");
        Long userId = params.get("userId") != null
                ? ((Number) params.get("userId")).longValue() : 0L;

        QueryWrapper query = QueryWrapper.create().eq("kbId", kbId);
        List<CourseDocumentChunk> chunks = courseDocumentChunkService.list(query);

        int success = 0;
        int fail = 0;

        for (CourseDocumentChunk chunk : chunks) {
            try {
                List<Double> embedding = embeddingUtils.embed(chunk.getContent());
                if (embedding != null && !embedding.isEmpty()) {
                    vectorStoreService.upsertChunkEmbedding(
                        userId,
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
                    log.warn("补向量：embedding 返回空, chunkId={}", chunk.getChunkId());
                }
            } catch (Exception e) {
                fail++;
                log.error("补向量单条失败, chunkId={}", chunk.getChunkId(), e);
            }
        }

        log.info("历史数据补向量完成, kbId={}, total={}, success={}, fail={}",
                kbId, chunks.size(), success, fail);

        return Map.of("total", chunks.size(), "success", success, "fail", fail);
    }

    /**
     * 导入本地文件夹中的 txt/md 文件到知识库
     */
    @PostMapping("/import-local-folder")
    public Map<String, Object> importLocalFolder(@RequestBody Map<String, Object> params) {
        String kbId = (String) params.get("kbId");
        Long userId = params.get("userId") != null
                ? ((Number) params.get("userId")).longValue() : 0L;
        String folderPath = (String) params.get("folderPath");

        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            throw new RuntimeException("目录不存在或不是文件夹: " + folderPath);
        }

        File[] files = folder.listFiles((dir, name) ->
                name.endsWith(".txt") || name.endsWith(".md"));

        if (files == null || files.length == 0) {
            log.warn("目录下无 txt/md 文件, folderPath={}", folderPath);
            return Map.of("totalFiles", 0, "successFiles", 0, "failFiles", 0,
                    "totalChunks", 0, "successEmbeddings", 0, "failEmbeddings", 0);
        }

        log.info("开始导入本地文件夹, kbId={}, folderPath={}, fileCount={}",
                kbId, folderPath, files.length);

        int totalFiles = 0, successFiles = 0, failFiles = 0;
        int totalChunks = 0, successEmbeddings = 0, failEmbeddings = 0;

        for (File file : files) {
            totalFiles++;
            String fileName = file.getName();
            String fileType = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

            try {
                String content = parseLocalFile(file, fileType);
                List<String> chunks = documentChunker.split(content);
                totalChunks += chunks.size();
                String docId = IdUtil.simpleUUID();

                // 保存文档
                CourseDocument doc = new CourseDocument();
                doc.setDocId(docId);
                doc.setKbId(kbId);
                doc.setUserId(userId);
                doc.setFileName(fileName);
                doc.setFileType(fileType);
                doc.setFileSize(file.length());
                doc.setParseStatus("SUCCESS");
                doc.setChunkCount(chunks.size());
                doc.setCreateTime(LocalDateTime.now());
                courseDocumentService.save(doc);

                // 保存切片
                List<CourseDocumentChunk> chunkEntities = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    CourseDocumentChunk chunk = new CourseDocumentChunk();
                    chunk.setChunkId(IdUtil.simpleUUID());
                    chunk.setKbId(kbId);
                    chunk.setDocId(docId);
                    chunk.setChunkIndex(i);
                    chunk.setContent(chunks.get(i));
                    chunk.setTokenCount(chunks.get(i).length());
                    chunk.setCreateTime(LocalDateTime.now());
                    chunkEntities.add(chunk);
                }
                courseDocumentChunkService.saveBatch(chunkEntities);

                // 生成 embedding 写入 PGVector
                for (CourseDocumentChunk chunk : chunkEntities) {
                    try {
                        List<Double> embedding = embeddingUtils.embed(chunk.getContent());
                        if (embedding != null && !embedding.isEmpty()) {
                            vectorStoreService.upsertChunkEmbedding(
                                userId, kbId, docId,
                                chunk.getChunkId(), chunk.getContent(),
                                embedding, "text-embedding-v4"
                            );
                            successEmbeddings++;
                        } else {
                            failEmbeddings++;
                            log.warn("导入：embedding 返回空, chunkId={}, file={}",
                                    chunk.getChunkId(), fileName);
                        }
                    } catch (Exception e) {
                        failEmbeddings++;
                        log.error("导入：embedding 写入 PGVector 失败, chunkId={}, file={}",
                                chunk.getChunkId(), fileName, e);
                    }
                }

                successFiles++;
                log.info("导入文件成功, file={}, docId={}, chunkCount={}",
                        fileName, docId, chunks.size());

            } catch (Exception e) {
                failFiles++;
                log.error("导入文件失败, file={}, kbId={}", fileName, kbId, e);
            }
        }

        // 更新知识库统计
        try {
            QueryWrapper kbQuery = QueryWrapper.create().eq("kbId", kbId);
            CourseKnowledgeBase kb = courseKnowledgeBaseService.getOne(kbQuery);
            if (kb != null) {
                kb.setDocumentCount((kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) + successFiles);
                kb.setChunkCount((kb.getChunkCount() == null ? 0 : kb.getChunkCount()) + totalChunks);
                courseKnowledgeBaseService.updateById(kb);
            }
        } catch (Exception e) {
            log.error("更新知识库统计失败, kbId={}", kbId, e);
        }

        log.info("本地文件夹导入完成, kbId={}, totalFiles={}, success={}, fail={}, chunks={}, embedSuccess={}, embedFail={}",
                kbId, totalFiles, successFiles, failFiles, totalChunks, successEmbeddings, failEmbeddings);

        return Map.of("totalFiles", totalFiles,
                "successFiles", successFiles,
                "failFiles", failFiles,
                "totalChunks", totalChunks,
                "successEmbeddings", successEmbeddings,
                "failEmbeddings", failEmbeddings);
    }

    /**
     * 读取本地文件内容，包装为 MultipartFile 后通过 DocumentParser 解析
     */
    private String parseLocalFile(File file, String fileType) throws IOException {
        byte[] content = Files.readAllBytes(file.toPath());
        String originalFilename = file.getName();
        MultipartFile mockFile = new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return originalFilename; }
            @Override public String getContentType() { return "text/plain"; }
            @Override public boolean isEmpty() { return content.length == 0; }
            @Override public long getSize() { return content.length; }
            @Override public byte[] getBytes() { return content; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
            @Override public void transferTo(File dest) throws IOException {
                Files.write(dest.toPath(), content);
            }
        };
        return documentParser.parse(mockFile, fileType);
    }
}

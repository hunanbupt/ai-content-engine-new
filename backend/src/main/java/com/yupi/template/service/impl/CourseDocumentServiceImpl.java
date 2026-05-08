package com.yupi.template.service.impl;

import cn.hutool.core.util.IdUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yupi.template.exception.ErrorCode;
import com.yupi.template.exception.ThrowUtils;
import com.yupi.template.mapper.CourseDocumentMapper;
import com.yupi.template.model.entity.CourseDocument;
import com.yupi.template.model.entity.CourseDocumentChunk;
import com.yupi.template.model.entity.CourseKnowledgeBase;
import com.yupi.template.model.entity.User;
import com.yupi.template.model.enums.DocumentParseStatusEnum;
import com.yupi.template.model.vo.CourseDocumentVO;
import com.yupi.template.rag.DocumentChunker;
import com.yupi.template.rag.DocumentParser;
import com.yupi.template.rag.EmbeddingUtils;
import com.yupi.template.service.CourseDocumentChunkService;
import com.yupi.template.service.CourseDocumentService;
import com.yupi.template.service.CourseKnowledgeBaseService;
import com.yupi.template.service.VectorStoreService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 课程文档服务实现类
 *
 * @author <a href="https://codefather.cn">编程导航学习圈</a>
 */
@Service
@Slf4j
public class CourseDocumentServiceImpl extends ServiceImpl<CourseDocumentMapper, CourseDocument>
        implements CourseDocumentService {

    /**
     * 最大文件大小：10MB
     */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * 允许的文件类型
     */
    private static final List<String> ALLOWED_FILE_TYPES = List.of("txt", "md");

    @Resource
    private CourseKnowledgeBaseService courseKnowledgeBaseService;

    @Resource
    private CourseDocumentChunkService courseDocumentChunkService;

    @Resource
    private DocumentParser documentParser;

    @Resource
    private DocumentChunker documentChunker;

    @Resource
    private EmbeddingUtils embeddingUtils;

    @Resource
    private VectorStoreService vectorStoreService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CourseDocumentVO uploadDocument(String kbId, MultipartFile file, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(kbId == null || kbId.trim().isEmpty(), ErrorCode.PARAMS_ERROR, "知识库ID不能为空");
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "文件不能为空");
        ThrowUtils.throwIf(file.getOriginalFilename() == null || file.getOriginalFilename().trim().isEmpty(),
                ErrorCode.PARAMS_ERROR, "文件名不能为空");

        // 2. 校验文件大小
        ThrowUtils.throwIf(file.getSize() > MAX_FILE_SIZE,
                ErrorCode.PARAMS_ERROR, "文件大小不能超过10MB");

        // 3. 校验文件类型（大小写不敏感）
        String originalFilename = file.getOriginalFilename();
        String fileType = getFileExtension(originalFilename).toLowerCase();
        ThrowUtils.throwIf(!ALLOWED_FILE_TYPES.contains(fileType),
                ErrorCode.PARAMS_ERROR, "不支持的文件类型，仅允许: " + String.join(", ", ALLOWED_FILE_TYPES));

        // 4. 校验知识库存在且属于当前用户
        CourseKnowledgeBase kb = validateKbOwnership(kbId, loginUser);

        // 5. 创建文档记录（状态先设为 PARSING）
        String docId = IdUtil.simpleUUID();
        CourseDocument document = buildDocument(docId, kbId, loginUser.getId(), originalFilename, fileType, file.getSize());
        this.save(document);

        try {
            // 6. 解析文档内容
            String content = documentParser.parse(file, fileType);

            // 7. 切片
            List<String> chunks = documentChunker.split(content);
            log.info("文档切片完成, docId={}, chunkCount={}", docId, chunks.size());

            // 8. 持久化切片
            List<CourseDocumentChunk> chunkEntities = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                CourseDocumentChunk chunk = new CourseDocumentChunk();
                chunk.setChunkId(IdUtil.simpleUUID());
                chunk.setKbId(kbId);
                chunk.setDocId(docId);
                chunk.setChunkIndex(i);
                chunk.setContent(chunks.get(i));
                chunk.setTokenCount(chunks.get(i).length()); // 粗略估算
                chunk.setCreateTime(LocalDateTime.now());
                chunkEntities.add(chunk);
            }
            courseDocumentChunkService.saveBatch(chunkEntities);

            // 8.5 为切片生成 embedding 并写入 PGVector（单个失败不影响上传）
            for (CourseDocumentChunk chunk : chunkEntities) {
                try {
                    List<Double> embedding = embeddingUtils.embed(chunk.getContent());
                    if (embedding != null && !embedding.isEmpty()) {
                        vectorStoreService.upsertChunkEmbedding(
                            loginUser.getId(),
                            kbId,
                            docId,
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
                    log.error("Chunk embedding 写入 PGVector 失败, chunkId={}, docId={}",
                            chunk.getChunkId(), docId, e);
                }
            }

            // 9. 更新文档状态为成功
            document.setParseStatus(DocumentParseStatusEnum.SUCCESS.getValue());
            document.setChunkCount(chunks.size());
            this.updateById(document);

            // 10. 更新知识库统计
            kb.setDocumentCount((kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) + 1);
            kb.setChunkCount((kb.getChunkCount() == null ? 0 : kb.getChunkCount()) + chunks.size());
            courseKnowledgeBaseService.updateById(kb);

            log.info("文档上传并解析成功, docId={}, kbId={}, chunkCount={}", docId, kbId, chunks.size());
            return toCourseDocumentVO(document);

        } catch (Exception e) {
            log.error("文档解析失败, docId={}, kbId={}", docId, kbId, e);

            // 更新文档状态为失败
            document.setParseStatus(DocumentParseStatusEnum.FAILED.getValue());
            document.setParseError(e.getMessage());
            this.updateById(document);

            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<CourseDocumentVO> listDocumentByKbId(String kbId, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(kbId == null || kbId.trim().isEmpty(), ErrorCode.PARAMS_ERROR, "知识库ID不能为空");

        // 校验知识库存在且属于当前用户
        validateKbOwnership(kbId, loginUser);

        // 查询文档列表（isDelete 由 MyBatis-Flex 逻辑删除自动过滤）
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("kbId", kbId)
                .orderBy("createTime", false);

        List<CourseDocument> list = this.list(queryWrapper);

        return list.stream()
                .map(this::toCourseDocumentVO)
                .collect(Collectors.toList());
    }

    // region 辅助方法

    /**
     * 校验知识库存在且属于当前用户
     */
    private CourseKnowledgeBase validateKbOwnership(String kbId, User loginUser) {
        QueryWrapper queryWrapper = QueryWrapper.create().eq("kbId", kbId);
        CourseKnowledgeBase kb = courseKnowledgeBaseService.getOne(queryWrapper);
        ThrowUtils.throwIf(kb == null, ErrorCode.NOT_FOUND_ERROR, "知识库不存在");
        ThrowUtils.throwIf(!kb.getUserId().equals(loginUser.getId()),
                ErrorCode.NO_AUTH_ERROR, "无权操作该知识库");
        return kb;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex == -1) {
            return "";
        }
        return filename.substring(dotIndex + 1);
    }

    /**
     * 构建 CourseDocument 实体
     */
    private CourseDocument buildDocument(String docId, String kbId, Long userId,
                                         String fileName, String fileType, long fileSize) {
        CourseDocument document = new CourseDocument();
        document.setDocId(docId);
        document.setKbId(kbId);
        document.setUserId(userId);
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFileSize(fileSize);
        document.setParseStatus(DocumentParseStatusEnum.PARSING.getValue());
        document.setChunkCount(0);
        document.setCreateTime(LocalDateTime.now());
        return document;
    }

    /**
     * Entity 转 VO
     */
    private CourseDocumentVO toCourseDocumentVO(CourseDocument document) {
        CourseDocumentVO vo = new CourseDocumentVO();
        vo.setId(document.getId());
        vo.setDocId(document.getDocId());
        vo.setKbId(document.getKbId());
        vo.setFileName(document.getFileName());
        vo.setFileType(document.getFileType());
        vo.setFileSize(document.getFileSize());
        vo.setParseStatus(document.getParseStatus());
        vo.setChunkCount(document.getChunkCount());
        vo.setCreateTime(document.getCreateTime());
        vo.setUpdateTime(document.getUpdateTime());
        return vo;
    }

    // endregion
}

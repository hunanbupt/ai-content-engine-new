package com.yupi.template.agent.agents;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.yupi.template.agent.tools.ImageGenerationTool;
import com.yupi.template.model.dto.article.ArticleState;
import com.yupi.template.utils.GsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图文合成 Agent
 * 将配图插入到正文的相应位置
 *
 * @author AI Passage Creator
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentMergerAgent implements NodeAction {

    public static final String INPUT_CONTENT = "content";
    public static final String INPUT_IMAGES = "images";
    public static final String OUTPUT_FULL_CONTENT = "fullContent";

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String content = state.value(INPUT_CONTENT)
                .map(Object::toString)
                .orElseThrow(() -> new IllegalArgumentException("缺少正文内容参数"));
        
        @SuppressWarnings("unchecked")
        List<ArticleState.ImageResult> images = state.value(INPUT_IMAGES)
                .map(v -> {
                    if (v instanceof List) {
                        List<?> list = (List<?>) v;
                        if (list.isEmpty()) {
                            return new ArrayList<ArticleState.ImageResult>();
                        }
                        // 检查列表元素类型
                        if (list.get(0) instanceof ArticleState.ImageResult) {
                            return (List<ArticleState.ImageResult>) v;
                        }
                        // 尝试转换
                        return convertToImageResults(list);
                    }
                    return new ArrayList<ArticleState.ImageResult>();
                })
                .orElse(new ArrayList<>());
        
        log.info("ContentMergerAgent 开始执行: 正文长度={}, 图片数量={}", content.length(), images.size());
        
        String fullContent = mergeImagesIntoContent(content, images);
        
        log.info("ContentMergerAgent 执行完成: 完整内容长度={}", fullContent.length());
        
        return Map.of(OUTPUT_FULL_CONTENT, fullContent);
    }

    /**
     * 将配图插入正文
     * 优先用占位符替换，占位符匹配不到时按 sectionTitle 定位章节插入，
     * 都匹配不到则追加到文末
     */
    private String mergeImagesIntoContent(String content, List<ArticleState.ImageResult> images) {
        if (images == null || images.isEmpty()) {
            return content;
        }

        String fullContent = content;
        List<ArticleState.ImageResult> unmatchedImages = new ArrayList<>();

        for (ArticleState.ImageResult image : images) {
            String placeholder = image.getPlaceholderId();
            log.info("处理图片: position={}, placeholderId={}, sectionTitle={}, url={}",
                    image.getPosition(), placeholder, image.getSectionTitle(), image.getUrl());

            if (placeholder != null && !placeholder.isEmpty()) {
                String description = image.getDescription() != null ? image.getDescription() : "配图";
                String imageMarkdown = "![" + description + "](" + image.getUrl() + ")";

                if (fullContent.contains(placeholder)) {
                    fullContent = fullContent.replace(placeholder, imageMarkdown);
                    log.info("成功替换占位符: {}", placeholder);
                } else {
                    log.warn("正文中未找到占位符: {}，尝试按章节标题定位", placeholder);
                    String result = insertImageBySectionTitle(fullContent, image);
                    if (result != null) {
                        fullContent = result;
                    } else {
                        unmatchedImages.add(image);
                    }
                }
            } else {
                log.info("图片 position={} 为封面图，不插入正文", image.getPosition());
            }
        }

        // 最终兜底：追加到文末
        if (!unmatchedImages.isEmpty()) {
            StringBuilder appendix = new StringBuilder("\n\n---\n\n");
            for (ArticleState.ImageResult image : unmatchedImages) {
                String sectionTitle = image.getSectionTitle();
                if (sectionTitle != null && !sectionTitle.isEmpty()) {
                    appendix.append("**").append(sectionTitle).append("**\n\n");
                }
                String description = image.getDescription() != null ? image.getDescription() : "配图";
                appendix.append("![").append(description).append("](").append(image.getUrl()).append(")\n\n");
            }
            fullContent += appendix.toString();
            log.info("已将 {} 张图片追加到文末", unmatchedImages.size());
        }

        return fullContent;
    }

    /**
     * 根据 sectionTitle 在正文中找到对应章节标题，将图片插入到该章节之后
     * 返回修改后的正文，找不到则返回 null
     */
    private String insertImageBySectionTitle(String content, ArticleState.ImageResult image) {
        String sectionTitle = image.getSectionTitle();
        if (sectionTitle == null || sectionTitle.isEmpty()) {
            return null;
        }

        String description = image.getDescription() != null ? image.getDescription() : "配图";
        String imageMarkdown = "\n\n![" + description + "](" + image.getUrl() + ")\n";

        // 在正文中查找 "## {sectionTitle}" 的章节标题
        String headingPattern = "## " + sectionTitle;
        int headingIndex = content.indexOf(headingPattern);
        if (headingIndex < 0) {
            // 尝试去掉首尾空格后再匹配
            String trimmedTitle = sectionTitle.trim();
            if (!trimmedTitle.equals(sectionTitle)) {
                headingPattern = "## " + trimmedTitle;
                headingIndex = content.indexOf(headingPattern);
            }
        }
        if (headingIndex < 0) {
            log.warn("正文中未找到章节标题: ## {}", sectionTitle);
            return null;
        }

        // 找到该章节标题所在行的结尾
        int lineEnd = content.indexOf('\n', headingIndex);
        if (lineEnd < 0) {
            lineEnd = content.length();
        }

        // 找到下一个 ## 标题的位置（下一个章节开头），图片插在这个位置之前
        int nextHeading = content.indexOf("\n## ", lineEnd + 1);
        if (nextHeading < 0) {
            // 没有下一个章节，插到文末
            log.info("按章节标题定位成功: sectionTitle={}, 插入位置=文末", sectionTitle);
            return content + imageMarkdown;
        }

        // 图片插入到下一个章节标题之前
        String before = content.substring(0, nextHeading);
        String after = content.substring(nextHeading);
        log.info("按章节标题定位成功: sectionTitle={}, 插入位置=下一个章节之前", sectionTitle);
        return before + imageMarkdown + after;
    }

    /**
     * 转换列表为 ImageResult 列表
     */
    private List<ArticleState.ImageResult> convertToImageResults(List<?> list) {
        List<ArticleState.ImageResult> results = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof ArticleState.ImageResult) {
                results.add((ArticleState.ImageResult) item);
            } else if (item instanceof ImageGenerationTool.ImageGenerationResult) {
                // 从 ImageGenerationTool.ImageGenerationResult 转换
                ImageGenerationTool.ImageGenerationResult genResult = 
                        (ImageGenerationTool.ImageGenerationResult) item;
                if (genResult.isSuccess()) {
                    ArticleState.ImageResult imageResult = new ArticleState.ImageResult();
                    imageResult.setPosition(genResult.getPosition());
                    imageResult.setUrl(genResult.getUrl());
                    imageResult.setMethod(genResult.getMethod());
                    imageResult.setKeywords(genResult.getKeywords());
                    imageResult.setSectionTitle(genResult.getSectionTitle());
                    imageResult.setDescription(genResult.getDescription());
                    imageResult.setPlaceholderId(genResult.getPlaceholderId());
                    results.add(imageResult);
                }
            } else if (item instanceof Map) {
                // 从 Map 转换
                String json = GsonUtils.toJson(item);
                ArticleState.ImageResult imageResult = GsonUtils.fromJson(json, ArticleState.ImageResult.class);
                if (imageResult.getUrl() != null) {
                    results.add(imageResult);
                }
            }
        }
        return results;
    }
}

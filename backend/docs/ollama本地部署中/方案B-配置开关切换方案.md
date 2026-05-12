# Ollama 本地部署切换方案 B — 配置开关

## 概述

不删除原有 DashScope 配置，通过 `ai.provider` 开关在 DashScope 和 Ollama 之间自由切换。

---

## 1. application.yml 改动

在 `spring.ai` 同级新增一行开关（**不改动**原有的 `dashscope` 和 `ollama` 配置段）：

```yml
spring:
  ai:
    # 已有的 DashScope 配置，保留不动
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:sk-faf70200343c4d929c4a5f88c2f42d99}
      chat:
        options:
          model: qwen-max
    # 已有的 Ollama 配置，保留不动
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3.1:8b-instruct-q4_0
          temperature: 0.7
          max-tokens: 1024
          timeout: 5000
          num-ctx: 4096

# ====== 新增：AI 提供商开关 ======
ai:
  provider: ollama   # ollama | dashscope
```

切换只需改 `provider` 的值，无需动其他配置。

---

## 2. 新增配置类 `AiProviderConfig.java`

放在 `com.yupi.template.config` 包下，根据 `ai.provider` 的值创建对应的 `ChatModel` Bean：

```java
package com.yupi.template.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiProviderConfig {

    /**
     * 当 ai.provider=ollama 时生效
     */
    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "ollama")
    public ChatModel ollamaChatModel(OllamaChatModel ollamaChatModel) {
        return ollamaChatModel;
    }

    /**
     * 当 ai.provider=dashscope 时生效（默认值）
     */
    @Bean
    @ConditionalOnProperty(name = "ai.provider", havingValue = "dashscope", matchIfMissing = true)
    public ChatModel dashscopeChatModel(DashScopeChatModel dashScopeChatModel) {
        return dashScopeChatModel;
    }
}
```

- `matchIfMissing = true` 表示没有配置 `ai.provider` 时默认走 DashScope，兼容旧配置。
- 两个 Bean 的方法名不同，但返回类型都是 `ChatModel`，业务代码无需关注具体实现。

---

## 3. 业务代码改动（6 个文件）

所有注入 `DashScopeChatModel` 的地方，改为通用的 `ChatModel` 接口：

```java
// 改前
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
@Resource
private DashScopeChatModel chatModel;

// 改后
import org.springframework.ai.chat.model.ChatModel;
@Resource
private ChatModel chatModel;
```

涉及文件清单：

| 文件 | 说明 |
|---|---|
| `service/ArticleAgentService.java` | 文章生成服务（同步/流式调用） |
| `service/SvgDiagramService.java` | SVG 图表生成 |
| `agent/agents/TitleGeneratorAgent.java` | 标题生成 Agent |
| `agent/agents/OutlineGeneratorAgent.java` | 大纲生成 Agent |
| `agent/agents/ContentGeneratorAgent.java` | 内容生成 Agent |
| `agent/agents/ImageAnalyzerAgent.java` | 图片分析 Agent |

---

## 4. pom.xml 改动

在已有 `spring-ai-alibaba-starter-dashscope` 的基础上，**新增** Ollama Starter：

```xml
<!-- 新增：Spring AI Ollama Starter -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
    <!-- 版本与 Spring AI Alibaba 保持一致，通过 dependencyManagement 管理 -->
</dependency>
```

> Spring AI 的 BOM 版本由 `spring-ai-alibaba-starter-dashscope` 传递引入，Ollama Starter 可复用同一版本号。

---

## 5. Embedding 同步切换（可选）

`rag/EmbeddingUtils.java` 中使用了 DashScope Embedding API。如需同步切换，可扩展 `AiProviderConfig`：

```java
@Bean
@ConditionalOnProperty(name = "ai.provider", havingValue = "ollama")
public EmbeddingModel ollamaEmbedding(OllamaEmbeddingModel ollamaEmbedding) {
    return ollamaEmbedding;
}

@Bean
@ConditionalOnProperty(name = "ai.provider", havingValue = "dashscope", matchIfMissing = true)
public EmbeddingModel dashscopeEmbedding(DashScopeEmbeddingModel embedding) {
    return embedding;
}
```

> **注意**：Ollama 的 embedding 模型（如 `nomic-embed-text`）输出维度与 DashScope `text-embedding-v4` 不同，切换后 PGVector 索引需重建。

---

## 切换操作步骤

1. `application.yml` 中修改 `ai.provider` 值
2. 重启服务
3. 运行 `OllamaConnectivityTest` 验证连通性
4. 确认业务功能正常
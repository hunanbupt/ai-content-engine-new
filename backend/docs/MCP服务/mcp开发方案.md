# MCP 工具调用开发方案

> 版本：v1.0  
> 日期：2026-05-08  
> 状态：待评审

---

## 目录

1. [项目整体流程设计](#1-项目整体流程设计)
2. [技术选型与依赖说明](#2-技术选型与依赖说明)
3. [开发步骤规划](#3-开发步骤规划)
4. [验收要点](#4-验收要点)

---

## 1. 项目整体流程设计

### 1.1 当前本地 @Tool 调用和 MCP 的区别

| 维度 | 当前本地 @Tool（ImageGenerationTool） | MCP 工具调用 |
|------|--------------------------------------|-------------|
| **调用方式** | `generateImageDirect()` 被 `ParallelImageGenerator` 直接编程调用，不走 LLM 决策 | LLM 在生成过程中**自主决定**何时调用工具，通过 MCP 协议发起远程调用 |
| **工具注册** | `@Tool` 注解仅标记，Spring AI Alibaba 框架可发现，但当前未通过 tool calling 路径使用 | 工具在 MCP Server 注册，通过 `tools/list` + `tools/call` 协议暴露给 MCP Client |
| **调用链路** | `NodeAction.apply()` → `ImageGenerationTool.generateImageDirect()` → 返回 Java 对象 | `LLM 决策` → `MCP Client 发送 tools/call` → `MCP Server 执行` → `返回 JSON-RPC 结果` |
| **网络边界** | 同进程内调用 | **跨进程**调用（可同机不同端口，可远程） |
| **LLM 角色** | LLM 仅负责输出图片需求 JSON，不解码工具 | LLM 原生 function calling 能力选择工具 + 构造参数 |
| **适用场景** | 确定性的图片生成流程，位置/类型/来源由前置 Agent 分析好 | **动态检索场景**：LLM 在写作过程中按需查知识库，自主决定关键词和时机 |

**核心区别**：当前是 "LLM 输出结构化 JSON，程序解析后手动调用工具"（伪 tool calling），MCP 是 "LLM 通过 function calling 协议主动调用远程工具"（真 tool calling）。

### 1.2 为什么 RAG 检索适合抽成 MCP Tool

1. **检索时机不确定**：当前 `enrichStateWithRagContext()` 在 Agent 启动前用 topic 做关键词检索一次，注入到 prompt 里。但 LLM 在写作时可能写到某个具体章节才发现需要额外知识——此时固定 topic 检索已不够用，LLM 需要在生成过程中**动态换关键词**再次检索。

2. **替代关键词检索更精准**：LLM 写"微服务架构设计"章节时，可以自行调用 `rag_search("微服务服务发现与注册")`，比初始 topic="后端开发"的一刀切检索精准得多。

3. **工具语义清晰**：`rag_search_user_knowledge` 是典型的 "read-only 知识查询" 工具，不修改数据库、不扣费、无副作用，天然适合 MCP 暴露。

4. **解耦检索逻辑**：MCP Server 可独立部署、独立扩缩容、独立升级检索算法，不影响主流程。

### 1.3 整体架构

```
                         ┌─────────────────────────────────┐
                         │        MCP Server (独立进程)      │
                         │  ┌───────────────────────────┐  │
                         │  │ RagMcpTool                │  │
                         │  │  - rag_search_user_knowledge │  │
                         │  │  - 自动选择知识库            │  │
                         │  │  - 调用 RagService          │  │
                         │  └───────────────────────────┘  │
                         │  MCP Protocol (HTTP/SSE)        │
                         └──────────────┬──────────────────┘
                                        │  tools/list, tools/call
                                        │  (JSON-RPC over HTTP)
                         ┌──────────────┴──────────────────┐
                         │    主应用 (Article Service)       │
                         │  ┌───────────────────────────┐  │
                         │  │ MCP Client (自动发现工具)    │  │
                         │  └───────────────────────────┘  │
                         │  ┌───────────────────────────┐  │
                         │  │ ToolCallingArticleAgent    │  │
                         │  │  - DashScopeChatModel      │  │
                         │  │  - 绑定 MCP 工具            │  │
                         │  │  - LLM 自主决定调用工具      │  │
                         │  └───────────────────────────┘  │
                         │  ┌───────────────────────────┐  │
                         │  │ ArticleAsyncService        │  │
                         │  │  - 路由 toolCallingEnabled │  │
                         │  │  - SSE 流式输出            │  │
                         │  │  - 失败降级                │  │
                         │  └───────────────────────────┘  │
                         └─────────────────────────────────┘
```

### 1.4 流程阶段

#### 阶段 A：文章创建（不变）
- 用户传入新增字段 `toolCallingEnabled`、`ragMode`
- Controller → Service → 保存到 `article` 表

#### 阶段 B：Phase1/Phase2（不变）
- 标题生成、大纲生成流程**不做任何修改**
- RAG 预检索 `enrichStateWithRagContext()` **保留**（作为 MCP 失败后的兜底）

#### 阶段 C：Phase3 正文生成（新增 MCP 路径）
```
executePhase3(taskId)
  ├─ toolCallingEnabled=false → 走原有 ContentGeneratorAgent（不变）
  └─ toolCallingEnabled=true  → 走 ToolCallingArticleAgent（新增）
       ├─ enrichStateWithRagContext() 仍执行（兜底数据）
       ├─ 构建含 MCP 工具的 ChatModel
       ├─ LLM 流式生成正文
       │    └─ LLM 需要知识时 → 主动调用 rag_search_user_knowledge (MCP)
       ├─ MCP 调用成功 → 知识注入 prompt，继续生成
       ├─ MCP 调用失败 → 降级为普通正文生成（使用预检索 ragContext）
       │    └─ ragMode=AUTO 时，额外使用后端 RagService 兜底检索
       ├─ 正文完成后 → 继续走 ImageAnalyzerAgent（不变）
       └─ SSE 流式推送（不变）
```

### 1.5 MCP Server 和 MCP Client 拆分

| 组件 | 部署 | 职责 | 关键类 |
|------|------|------|--------|
| **MCP Server** | 同机独立进程（端口 8124）或同应用内嵌 | 暴露 `rag_search_user_knowledge` 工具，调用 `RagService` 检索 | `RagMcpTool`, `McpServerApplication` |
| **MCP Client** | 主应用内（端口 8123） | 连接 MCP Server，自动发现工具，注入 `ToolCallingArticleAgent` | `McpClientConfig`, `ToolCallingArticleAgent` |

**拆分理由**：
- MCP Server 作为独立进程，工具逻辑与主流程解耦
- 后续可扩展更多 MCP Tool（如联网搜索、图片检索等）而不修改主应用
- 检索逻辑升级（换 embedding 模型、换向量数据库）只改 Server
- 第一版同机部署简化运维，后续可拆分到独立机器

---

## 2. 技术选型与依赖说明

### 2.1 Maven 新增依赖

```xml
<!-- Spring AI MCP Server (WebMVC 传输) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    <version>1.0.0-M6</version>
</dependency>

<!-- Spring AI MCP Client -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
    <version>1.0.0-M6</version>
</dependency>
```

**选型原因**：
- 项目已使用 Spring AI Alibaba 1.1.0.0-RC2（基于 Spring AI 1.0.0-M6+），MCP 模块版本**必须对齐底层 Spring AI 版本**
- `webmvc` 传输：MCP Server 使用 HTTP + SSE 传输，无需引入 WebFlux，与项目现有 WebMVC 体系一致
- 不引入新语言/新框架，全部在 Spring Boot 生态内

### 2.2 关键依赖兼容性验证点

| 依赖 | 当前版本 | MCP 要求 | 验证方式 |
|------|---------|---------|---------|
| `spring-ai-alibaba-starter-dashscope` | 1.1.0.0-RC2 | 底层 Spring AI ≥ 1.0.0-M5 | 检查传递依赖 `spring-ai-core` 版本 |
| `spring-boot-starter-web` | 3.5.9 | 3.4.x+ | 已满足 |
| `mybatis-flex` | 1.11.1 | 无特殊要求 | 已满足 |

**注意**：如果 `spring-ai-alibaba-starter-dashscope:1.1.0.0-RC2` 传递的 Spring AI 版本与 `1.0.0-M6` 不一致，需要在 pom 中显式声明 `<dependencyManagement>` 统一版本。

### 2.3 application.yml 新增配置

```yaml
# MCP Client 配置
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: article-mcp-client
        version: 1.0.0
        type: SYNC  # 正文生成需要同步等待工具结果
        request-timeout: 30s
        connections:
          rag-server:
            url: http://localhost:8124/api/mcp  # MCP Server 地址

# MCP Server 配置（仅在 mcp-server profile 激活时生效）
mcp:
  server:
    port: 8124
    context-path: /api/mcp
```

---

## 3. 开发步骤规划

### 总览：7 个步骤，按优先级顺序执行

```
Step 1: 数据库变更 + 实体改造          (基础，必须先做)
Step 2: MCP Server 搭建                (独立可测)
Step 3: MCP Client 集成                (连接 Step 2)
Step 4: ToolCallingArticleAgent 开发   (核心业务)
Step 5: ArticleAsyncService 路由改造   (接入主流程)
Step 6: 监控与日志                     (可观测性)
Step 7: 测试用例                       (验收)
```

---

### Step 1：数据库变更 + 实体改造

**优先级**：P0（基础依赖）  
**预估工时**：0.5h

#### 1.1 数据库 DDL

```sql
-- 为 article 表新增两个字段
ALTER TABLE article
  ADD COLUMN tool_calling_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用 MCP 工具调用：0 不启用 / 1 启用',
  ADD COLUMN rag_mode VARCHAR(32) NOT NULL DEFAULT 'INLINE' COMMENT 'RAG 模式：INLINE（预检索注入Prompt）/ MCP（MCP工具调用）/ AUTO（MCP优先+后端兜底）';
```

#### 1.2 Article 实体改造

**文件**：`backend/src/main/java/com/yupi/template/model/entity/Article.java`

新增两个字段：
```java
/**
 * 是否启用 MCP 工具调用：0 不启用 / 1 启用
 */
private Integer toolCallingEnabled;

/**
 * RAG 模式：INLINE / MCP / AUTO
 */
private String ragMode;
```

#### 1.3 ArticleState DTO 改造

**文件**：`backend/src/main/java/com/yupi/template/model/dto/article/ArticleState.java`

新增：
```java
private boolean toolCallingEnabled;
private String ragMode;
```

#### 1.4 ArticleCreateRequest DTO 改造

**文件**：`backend/src/main/java/com/yupi/template/model/dto/article/ArticleCreateRequest.java`（需确认路径）

新增字段：
```java
private Boolean toolCallingEnabled;  // 默认 false
private String ragMode;              // 默认 "INLINE"
```

#### 1.5 数据库迁移脚本

**文件**：`backend/sql/mcp_init.sql`（新增）

```sql
ALTER TABLE article
  ADD COLUMN IF NOT EXISTS tool_calling_enabled TINYINT NOT NULL DEFAULT 0 
    COMMENT '是否启用 MCP 工具调用：0 不启用 / 1 启用',
  ADD COLUMN IF NOT EXISTS rag_mode VARCHAR(32) NOT NULL DEFAULT 'INLINE' 
    COMMENT 'RAG 模式：INLINE/MCP/AUTO';
```

**验收点**：
- [ ] `article` 表新增两个字段，默认值正确
- [ ] `Article.java` 编译通过，MyBatis-Flex 自动映射
- [ ] `ArticleState.java` 新增字段可传递
- [ ] 创建文章请求可接收新字段

---

### Step 2：MCP Server 搭建

**优先级**：P0（MCP Client 的前置依赖）  
**预估工时**：2h

#### 2.1 新增文件清单

| 文件 | 职责 |
|------|------|
| `agent/tools/RagMcpTool.java` | MCP 工具类，暴露 `rag_search_user_knowledge` |
| `config/McpServerConfig.java` | MCP Server 配置（非 profile 模式时禁用） |
| `mcp/McpServerApplication.java` | （可选）独立启动类，仅 profile=mcp-server 时启用 |

#### 2.2 RagMcpTool 设计

**核心逻辑**：

```
rag_search_user_knowledge(query, kbId?, topK?)
  │
  ├─ kbId 为空 → 自动选择知识库
  │   ├─ 根据 userId 查询 CourseKnowledgeBase 列表
  │   ├─ 选择 status=ENABLED 且关联了文档的 kb
  │   └─ 多个 kb 时，选择最近更新的
  │
  ├─ 权限校验
  │   └─ 校验 kbId 归属当前 userId（通过 Article 关联的 userId 传入）
  │
  ├─ 调用 RagService.retrieveByUserId(kbId, query, topK, userId)
  │
  └─ 返回检索结果（JSON）
      ├─ chunks: [{chunkId, content, score}, ...]
      └─ kbId, totalCount
```

**关键约束**：
- **不修改数据库主状态**：只读 `CourseKnowledgeBase`、`CourseDocumentChunk`
- **不处理扣费、权限变更、删除操作**
- **所有查询使用 MyBatis-Flex**（不是 MyBatis-Plus）
- **不使用真实向量数据库/Embedding**：沿用 `EmbeddingUtils.calculateTextSimilarity()` 关键词匹配

```java
@Component
public class RagMcpTool {

    @Resource
    private RagService ragService;
    @Resource
    private CourseKnowledgeBaseService kbService;
    @Resource
    private CourseDocumentService docService;

    @Tool(description = "在用户的知识库中检索相关知识片段。当你在写作过程中需要引用"
            + "课程知识点、技术细节或准确数据时调用此工具。")
    public RagSearchResponse ragSearchUserKnowledge(
            @ToolParam(description = "检索查询文本，建议使用具体的关键词或问题") 
            String query,
            @ToolParam(description = "知识库ID，留空则自动选择用户的知识库") 
            String kbId,
            @ToolParam(description = "返回结果数量，默认5，最大10") 
            Integer topK,
            @ToolParam(description = "用户ID，由 MCP Client 透传") 
            Long userId) {
        
        // 1. 自动选择知识库
        if (kbId == null || kbId.isEmpty()) {
            kbId = autoSelectKb(userId);
            if (kbId == null) {
                return RagSearchResponse.empty("未找到可用知识库");
            }
        }
        
        // 2. 参数规范化
        if (topK == null || topK < 1) topK = 5;
        if (topK > 10) topK = 10;
        
        // 3. 检索
        List<RetrievedChunk> chunks = ragService.retrieveByUserId(
                kbId, query, topK, userId);
        
        // 4. 构造响应
        return RagSearchResponse.of(query, kbId, chunks);
    }
    
    private String autoSelectKb(Long userId) {
        // 查询用户的启用状态知识库
        // 按 update_time 降序，取第一个
    }
}
```

#### 2.3 MCP Server 配置

```java
@Configuration
@ConditionalOnProperty(name = "mcp.server.enabled", havingValue = "true")
public class McpServerConfig {
    // Spring AI MCP Server 自动配置会扫描 @Tool 注解
    // RagMcpTool 会被自动注册为 MCP 工具
}
```

**验收点**：
- [ ] `RagMcpTool` 的 `@Tool` 方法可通过 MCP 协议的 `tools/list` 发现
- [ ] `tools/call` 调用 `rag_search_user_knowledge` 返回正确检索结果
- [ ] `kbId` 为空时自动选择知识库
- [ ] 权限校验：只能查自己的 kb
- [ ] 空结果返回空列表而非报错

---

### Step 3：MCP Client 集成

**优先级**：P1  
**预估工时**：1.5h

#### 3.1 新增文件清单

| 文件 | 职责 |
|------|------|
| `config/McpClientConfig.java` | MCP Client 配置，连接 MCP Server，注入工具到 ChatModel |

#### 3.2 McpClientConfig 设计

```java
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true")
public class McpClientConfig {

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(
            McpSyncClient mcpClient) {
        // 通过 MCP Client 自动发现 MCP Server 的工具
        // 包装为 ToolCallback 供 DashScopeChatModel 使用
        var toolCallbacks = mcpClient.listTools()
                .stream()
                .map(tool -> new McpToolCallback(mcpClient, tool))
                .toList();
        return ToolCallbackProvider.from(toolCallbacks);
    }
}
```

**注意**：
- 使用 `McpSyncClient`（同步），因为正文生成需要等待工具返回结果才能继续
- MCP Client 连接 URL 指向本机 8124 端口（或可配置）

**验收点**：
- [ ] 应用启动时 MCP Client 成功连接 MCP Server
- [ ] `tools/list` 发现 `rag_search_user_knowledge` 工具
- [ ] MCP Server 不可用时 Client 启动不崩溃（降级）

---

### Step 4：ToolCallingArticleAgent 开发

**优先级**：P1（核心业务）  
**预估工时**：3h

#### 4.1 新增文件清单

| 文件 | 职责 |
|------|------|
| `agent/agents/ToolCallingArticleAgent.java` | 带 MCP 工具调用的正文生成 Agent |

#### 4.2 流程设计

```
ToolCallingArticleAgent.apply(state)
  │
  ├─ 1. 从 state 提取参数
  │   mainTitle, subTitle, outline, style, ragContext, kbId, ragMode, userId
  │
  ├─ 2. 构建 System Prompt
  │   - 文章生成指令（大纲 → 正文）
  │   - 工具使用指引："如果你需要引用课程中的知识点，使用 rag_search_user_knowledge 工具"
  │   - ragMode=AUTO 时的兜底说明："工具调用失败时可使用下方预检索知识"
  │
  ├─ 3. 构建 User Prompt
  │   - 大纲 JSON
  │   - ragContext（预检索结果，作为兜底附在 prompt 中）
  │
  ├─ 4. 配置 ChatModel
  │   - DashScopeChatModel + MCP ToolCallbacks
  │   - 流式模式 (stream)
  │
  ├─ 5. 调用 LLM（带 tool calling）
  │   - 使用 Spring AI ChatClient API
  │   - LLM 在生成过程中自主调用 rag_search_user_knowledge
  │   - 工具返回结果自动注入后续 prompt
  │   - 流式输出正文内容
  │
  ├─ 6. 异常处理
  │   ├─ MCP 连接超时 → catch MCP 异常
  │   ├─ 工具调用失败 → 使用预检索的 ragContext 作为替代
  │   └─ 所有异常兜底 → 降级为无 RAG 的普通正文生成
  │
  └─ 7. 返回 Map.of("content", content)
```

#### 4.3 关键代码结构

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class ToolCallingArticleAgent implements NodeAction {

    private final DashScopeChatModel chatModel;
    private final ToolCallbackProvider mcpToolCallbackProvider;

    public static final String INPUT_MAIN_TITLE = "mainTitle";
    public static final String INPUT_SUB_TITLE = "subTitle";
    public static final String INPUT_OUTLINE = "outline";
    public static final String INPUT_STYLE = "style";
    public static final String INPUT_RAG_CONTEXT = "ragContext";
    public static final String INPUT_KB_ID = "kbId";
    public static final String INPUT_USER_ID = "userId";
    public static final String INPUT_RAG_MODE = "ragMode";
    public static final String OUTPUT_CONTENT = "content";

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // ... 参数提取、Prompt 构建、LLM 调用、异常处理
    }

    /**
     * 降级生成：不使用 MCP 工具，使用预检索 RAG 上下文
     */
    private String degradeToNormalGeneration(String prompt, String ragContext) {
        // 将 ragContext 注入 prompt，调用 chatModel.stream()
    }
}
```

#### 4.4 降级策略

```
MCP 调用异常
  ├─ ragMode = "MCP"   → 使用 ragContext 预检索结果（enrichState 阶段已设置）
  ├─ ragMode = "AUTO"  → 先尝试 ragContext，再调用 RagService.retrieveByUserId() 作为二次兜底
  └─ ragMode = "INLINE" → 不使用 MCP（本不应进入此路径，但兼容处理）
```

**验收点**：
- [ ] `toolCallingEnabled=true` 时 LLM 能在正文生成中调用 `rag_search_user_knowledge`
- [ ] 工具调用返回的知识被正确嵌入后续生成
- [ ] MCP Server 不可用时**降级成功**，正文仍可产生
- [ ] 降级日志记录清晰（触发原因 + 降级路径）
- [ ] 流式输出正常，SSE 消息格式与现有兼容

---

### Step 5：ArticleAsyncService 路由改造

**优先级**：P1  
**预估工时**：1h

#### 5.1 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `service/ArticleAsyncService.java` | Phase3 增加 toolCallingEnabled 路由 |
| `agent/ArticleAgentOrchestrator.java` | Phase3 图增加 ToolCallingArticleAgent 节点 |
| `agent/config/AgentConfig.java` | 新增 toolCallingEnabled 配置项 |
| `controller/ArticleController.java` | createArticle 接口接收新字段 |
| `service/ArticleService.java` | 保存文章时处理新字段 |

#### 5.2 ArticleAsyncService 改造点

```java
// executePhase3 关键变更
public void executePhase3(String taskId) {
    // ... 现有逻辑 ...
    
    Article article = articleService.getByTaskId(taskId);
    
    // 新增：判断是否启用 MCP 工具调用
    boolean toolCallingEnabled = article.getToolCallingEnabled() != null 
            && article.getToolCallingEnabled() == 1;
    
    if (toolCallingEnabled && agentConfig.isOrchestratorEnabled()) {
        // 走 MCP 路径：使用新的 Phase3 图（含 ToolCallingArticleAgent）
        articleAgentOrchestrator.executePhase3_WithToolCalling(state, message -> {
            handleAgentMessage(taskId, message, state);
        });
    } else {
        // 走原有路径（不变）
        articleAgentOrchestrator.executePhase3_GenerateContent(state, message -> {
            handleAgentMessage(taskId, message, state);
        });
    }
}
```

#### 5.3 Orchestrator 新增方法

```java
// ArticleAgentOrchestrator 新增
public void executePhase3_WithToolCalling(ArticleState state, Consumer<String> streamHandler) {
    // 新增 graph: content_generator(ToolCalling) -> image_analyzer 
    //           -> parallel_image_generator -> content_merger
    // 仅第一个节点替换为 ToolCallingArticleAgent，后续图片流程不变
}
```

**验收点**：
- [ ] `toolCallingEnabled=false` 时走原流程，行为与改造前**完全一致**
- [ ] `toolCallingEnabled=true` + `orchestratorEnabled=true` 时走 MCP 路径
- [ ] `toolCallingEnabled=true` + `orchestratorEnabled=false` 时降级走旧路径（打 WARN 日志）
- [ ] SSE 消息格式不破环

---

### Step 6：监控与日志

**优先级**：P2  
**预估工时**：1h

#### 6.1 日志设计

| 日志位置 | 级别 | 内容 | 示例 |
|---------|------|------|------|
| `ToolCallingArticleAgent` 启动 | INFO | taskId, ragMode, kbId | `MCP工具调用开始 taskId=xxx ragMode=AUTO kbId=kb_123` |
| MCP 工具被 LLM 调用 | INFO | toolName, query, kbId | `LLM调用MCP工具 tool=rag_search_user_knowledge query="微服务注册中心" kbId=kb_123` |
| MCP 工具返回值 | DEBUG | toolName, 结果数量, 耗时 | `MCP工具返回 tool=rag_search_user_knowledge chunkCount=5 elapsed=120ms` |
| MCP 调用失败 | WARN | 失败原因, 降级路径 | `MCP工具调用失败 tool=rag_search_user_knowledge error="连接超时" 降级为预检索RAG` |
| MCP Server 启动 | INFO | 端口, 暴露工具数 | `MCP Server 启动 port=8124 tools=[rag_search_user_knowledge]` |
| MCP Client 连接 | INFO | 连接状态, 服务URL | `MCP Client 连接成功 url=http://localhost:8124/api/mcp` |
| 降级到普通生成 | WARN | taskId, 原因 | `MCP工具调用降级 taskId=xxx reason=MCP连接超时 fallback=预检索RAG` |
| 完整工具调用链 | INFO | taskId, 调用次数, 成功率 | `MCP工具调用统计 taskId=xxx totalCalls=3 successCalls=2 failCalls=1` |

#### 6.2 关键日志格式

```
[MCP-CLIENT] taskId={taskId} toolCall={"name":"rag_search_user_knowledge","arguments":{"query":"xxx","kbId":"yyy"}} result={"chunkCount":5,"elapsedMs":120}
[MCP-SERVER] tool=rag_search_user_knowledge userId={userId} kbId={kbId} query="{query}" topK=5 resultCount=3 elapsedMs=85
[MCP-FALLBACK] taskId={taskId} reason={reason} fallback={fallbackPath} 
```

**验收点**：
- [ ] MCP 调用链可追踪（taskId 贯穿 Client → Server）
- [ ] 降级事件有明确 WARN 日志
- [ ] 工具调用耗时可度量

---

### Step 7：测试用例

**优先级**：P2  
**预估工时**：2h

#### 7.1 单元测试

| 测试类 | 测试内容 |
|--------|---------|
| `RagMcpToolTest` | 自动选择知识库、正常检索、空结果、参数边界 |
| `ToolCallingArticleAgentTest` | Prompt 构建、降级逻辑、参数提取 |
| `McpClientConfigTest` | MCP Client 连接、工具发现 |

#### 7.2 集成测试

| 测试场景 | 前置条件 | 预期结果 |
|---------|---------|---------|
| **正常 MCP 调用** | MCP Server 正常，toolCallingEnabled=true | LLM 调用工具，知识注入正文 |
| **MCP Server 不可用** | MCP Server 停止 | 降级为预检索 RAG，正文正常生成 |
| **知识库为空** | 用户无知识库 | 工具返回空列表，LLM 继续无 RAG 生成 |
| **MCP 不启用** | toolCallingEnabled=false | 走原流程，行为不变 |
| **ragMode=AUTO 兜底** | MCP 失败，ragMode=AUTO | 走 RagService 后端兜底检索 |
| **SSE 流式推送** | 正常 MCP 调用 | SSE 消息格式兼容，前端不报错 |
| **配图流程不受影响** | toolCallingEnabled=true | 图片生成流程正常 |

#### 7.3 手动验证步骤

```
1. 启动 MCP Server（端口 8124）
2. 启动主应用（端口 8123）
3. 确认 MCP Client 日志：看到 "MCP Client 连接成功"
4. 创建文章：POST /api/article/create
   {
     "topic": "Spring Cloud 微服务架构",
     "toolCallingEnabled": true,
     "ragMode": "AUTO",
     "ragEnabled": 1,
     "kbId": "xxx"
   }
5. 观察 SSE 流式输出
6. 检查日志中是否有 "LLM调用MCP工具" 记录
7. 验证最终正文引用了知识库内容
```

---

## 4. 验收要点

### 4.1 功能验收

- [ ] **1** 保留现有 ArticleAsyncService 多阶段生成流程：Phase1/Phase2 完全不变，Phase3 原有路径不受影响
- [ ] **2** 保留 SSE：流式消息格式兼容，TYPE/SN/DATA 结构不变，重连机制不变
- [ ] **3** 保留配图流程：ImageAnalyzerAgent → ParallelImageGenerator → ContentMergerAgent 完整保留
- [ ] **4** `toolCallingEnabled` 字段：数据库 `article.tool_calling_enabled`，接口接收，状态传递，路由生效
- [ ] **5** `ragMode` 字段：数据库 `article.rag_mode`，接口接收，支持 INLINE/MCP/AUTO 三值
- [ ] **6** `ToolCallingArticleAgent`：实现 NodeAction，流式输出，带 MCP 工具调用能力
- [ ] **7** MCP Tool Server：独立部署，暴露 `rag_search_user_knowledge` 工具，通过 `tools/list` + `tools/call` 协议可调用
- [ ] **8** MCP Server 第一版只暴露 `rag_search_user_knowledge`：RagMcpTool 是唯一 @Tool
- [ ] **9** Article 服务作为 MCP Client：McpClientConfig 连接 MCP Server，自动发现工具
- [ ] **10** 正文阶段接入 MCP Tool Calling：Phase3 可通过 toolCallingEnabled 切换到 ToolCallingArticleAgent
- [ ] **11** MCP 调用失败降级：异常捕获 → WARN 日志 → 使用预检索 ragContext 继续生成
- [ ] **12** ragMode=AUTO 兜底：MCP 失败后额外调用 RagService 后端检索
- [ ] **13** MyBatis-Flex：所有数据库操作使用 MyBatis-Flex，不使用 MyBatis-Plus
- [ ] **14** 不引入真实向量数据库：延续 `EmbeddingUtils.calculateTextSimilarity()` 关键词匹配
- [ ] **15** 不引入真实 embedding：不接 DashScope Embedding API
- [ ] **16** MCP 工具不修改数据库主状态：只读查询
- [ ] **17** MCP 工具不处理扣费、权限、SSE、删除：这些操作仍在主应用 Service 层

### 4.2 非功能验收

- [ ] 原流程完全不受影响（`toolCallingEnabled=false` 时行为不变）
- [ ] MCP Server 不可用时主应用不崩溃
- [ ] 降级路径所有异常被捕获，不抛出到用户
- [ ] 所有 MCP 调用有 taskId 关联日志
- [ ] 不引入循环依赖（MCP Server 不依赖 MCP Client，Client 不依赖 Server 启动）
- [ ] 不修改 `PrompConstant` 中已有 prompt（新 prompt 在 ToolCallingArticleAgent 内定义）

### 4.3 本次不修改的内容

| 不修改项 | 原因 |
|---------|------|
| `ArticleAsyncService.executePhase1/executePhase2` | Phase1/Phase2 不涉及工具调用 |
| `SseEmitterManager` | SSE 机制不变 |
| `SseMessageTypeEnum` | 不新增消息类型，复用现有 AGENT3_STREAMING |
| `ContentGeneratorAgent` | 保留作为非 MCP 路径 |
| `ImageAnalyzerAgent` / `ParallelImageGenerator` / `ContentMergerAgent` | 配图流程不变 |
| `RagService` 接口和实现 | 只新增调用，不改签名 |
| `PromptConstant` | 不为 MCP 修改已有常量 |
| `ImageGenerationTool` | 保持在本地 `@Tool` 注解，暂不迁移到 MCP |
| `TitleGeneratorAgent` / `OutlineGeneratorAgent` | 标题和大纲阶段不接入 MCP |
| `前端代码` | 本阶段只改后端 |

---

## 附录 A：文件变更总览

### 新增文件（9 个）

```
backend/
├── sql/
│   └── mcp_init.sql                          # 数据库迁移脚本
├── src/main/java/com/yupi/template/
│   ├── agent/
│   │   └── agents/
│   │       └── ToolCallingArticleAgent.java   # MCP 正文生成 Agent
│   ├── agent/
│   │   └── tools/
│   │       └── RagMcpTool.java                # MCP RAG 检索工具
│   ├── config/
│   │   ├── McpServerConfig.java               # MCP Server 配置
│   │   └── McpClientConfig.java               # MCP Client 配置
│   └── mcp/
│       └── McpServerApplication.java          # MCP Server 独立启动类（可选）
```

### 修改文件（7 个）

```
backend/src/main/java/com/yupi/template/
├── model/
│   ├── entity/Article.java                    # 新增 toolCallingEnabled, ragMode
│   └── dto/article/ArticleState.java          # 新增 toolCallingEnabled, ragMode
├── agent/
│   ├── ArticleAgentOrchestrator.java          # 新增 executePhase3_WithToolCalling()
│   └── config/AgentConfig.java                # 新增 toolCallingEnabled 配置
├── service/
│   ├── ArticleAsyncService.java               # Phase3 增加 MCP 路由
│   └── ArticleService.java                    # 保存文章时处理新字段
└── controller/
    └── ArticleController.java                 # createArticle 接口接收新字段
```

---

## 附录 B：toolCallingEnabled 与 ragMode 配合矩阵

| toolCallingEnabled | ragMode | Phase3 行为 | RAG 检索时机 |
|-------------------|---------|------------|-------------|
| false | INLINE（默认） | 原 ContentGeneratorAgent | Phase 启动前 `enrichStateWithRagContext()` 预检索 |
| false | MCP | 降级为 INLINE（WARN 日志） | 同 INLINE |
| false | AUTO | 降级为 INLINE（WARN 日志） | 同 INLINE |
| true | INLINE | 降级为普通生成（WARN 日志） | 预检索 + 不发起 MCP 调用 |
| true | MCP | ToolCallingArticleAgent | LLM 动态调用 MCP，失败用预检索兜底 |
| true | AUTO | ToolCallingArticleAgent | LLM 动态调用 MCP，失败用预检索 + 后端 RagService 兜底 |

---

## 附录 C：风险与注意事项

1. **Spring AI 版本对齐**：`spring-ai-alibaba-starter-dashscope:1.1.0.0-RC2` 的传递依赖 Spring AI 版本需与 `spring-ai-starter-mcp-server-webmvc` / `spring-ai-starter-mcp-client` 的版本一致。如不一致，需在 `dependencyManagement` 中统一。

2. **DashScope Qwen-Max 工具调用稳定性**：Qwen-Max 的 function calling 能力需实测验证，可能在复杂场景下不调用工具或调用过频。

3. **Tool Callback 线程安全**：`StreamHandlerContext` ThreadLocal 在工具调用回调中可能丢失，需处理线程传递。

4. **MCP Server 内存占用**：MCP Server 独立进程会额外占用内存，需评估服务器资源。

5. **工具调用延迟**：每次 MCP 工具调用增加网络往返延迟（同机约 5-20ms），需控制 LLM 不频繁调用。
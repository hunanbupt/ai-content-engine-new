# RAG 自动知识库选择与调用决策 — 改进方案 v1

## 〇、关键约定

### 0.1 ragEnabled 类型约定

`ragEnabled` 在项目各层以不同类型存在，必须统一对待：

| 层级 | 文件 | 类型 | 取值 | 说明 |
|------|------|------|------|------|
| 数据库 | `article` 表 | `TINYINT` | `0` / `1` | DDL 定义 |
| Entity | `Article.java` | `Integer` | `0` / `1` | MyBatis-Flex 映射，与 DB 一致 |
| Request DTO | `ArticleCreateRequest.java` | `Boolean` | `true` / `false` / `null` | 面向调用方，更语义化 |
| State DTO | `ArticleState.java` | `Boolean` | `true` / `false` | 推理后的布尔值，Agent 使用 |

**转换点**：
- `ArticleServiceImpl.createArticleTask()`：`isRagEnabled ? 1 : 0` → `article.ragEnabled`（Boolean→Integer，写入 DB）
- `ArticleAsyncService.enrichStateWithRagContext()`：`article.getRagEnabled() != null && article.getRagEnabled() == 1` → `state.ragEnabled`（Integer→Boolean，读 DB 状态）

**本方案不再修改类型**，保持各层现有约定。新增的 `ragMode` 在各层统一为 `String`，直接存储枚举的 `value` 值。

### 0.2 ragMode 是主导字段，ragEnabled 退化为兼容字段

引入 `ragMode` 后，流程中**唯一决定是否调用 RAG 的是 `RagDecision.useRag`**。

- `article.ragEnabled` 仅在 `resolveRagMode()` 的向后兼容逻辑中使用（旧请求未传 ragMode 时据此推断模式）
- `state.ragEnabled` 由 `article.ragEnabled` 派生，供 Agent Prompt 构建路径读取（现有代码路径依赖此字段）
- AUTO 模式下 `article.ragEnabled` 会被设为 `1`（兼容写入），但 RAG 是否真正调用取决于 Router 是否匹配到知识库
- **结论**：AUTO 模式下 `ragEnabled=1` 不代表 RAG 一定生效，必须看 `RagDecision.useRag`

### 0.3 ON 模式 kbId 为空：创建时报错，运行时不兜底

**统一行为**：ON 模式在创建阶段就必须传 kbId，不传则 `throw BusinessException`。

- `ArticleServiceImpl.createArticleTask()`：ON 且 kbId 为空 → **抛异常**（参数校验，调用方立即感知错误）
- `RagDecisionService.decide()` 中 ON 分支：由于创建时已拦截，正常流程不会走到 kbId 为空的情况。保留空值判断仅作为防御性代码，日志级别用 `warn`（而非 `info`）

### 0.4 阶段名统一使用 ArticlePhaseEnum 的 value

本方案中 phase 参数使用 `ArticlePhaseEnum` 的 value 值（与 DB `article.phase` 列一致）：

| 本方案简称 | phase 参数值 | ArticlePhaseEnum |
|-----------|-------------|------------------|
| 阶段1 | `TITLE_GENERATING` | `TITLE_GENERATING("TITLE_GENERATING", "标题生成中")` |
| 阶段2 | `OUTLINE_GENERATING` | `OUTLINE_GENERATING("OUTLINE_GENERATING", "大纲生成中")` |
| 阶段3 | `CONTENT_GENERATING` | `CONTENT_GENERATING("CONTENT_GENERATING", "正文生成中")` |

### 0.5 KnowledgeBaseRouterService：接口 + 实现类

采用接口 + 实现类分离，与项目现有 `RagService` / `RagServiceImpl` 模式一致。

### 0.6 RagDecisionService：直接 @Service，不需要接口

采用具体类直接注入，与项目现有 `ArticleAsyncService` 模式一致。无多实现需求，无需抽象接口。

---

## 一、项目整体流程设计

### 1.1 当前流程（改进前）

```
用户创建文章 → 手动传 ragEnabled=true + kbId → ArticleAsyncService.enrichStateWithRagContext()
  → 读取 ragEnabled/kbId → 调用 RagService.buildRagContextByUserId(kbId, query, topK, userId)
  → 写入 state.ragContext → Agent Prompt 注入
```

**痛点**：用户必须手动指定 kbId，且无法判断该 kbId 是否与文章主题相关。

### 1.2 目标流程（改进后）

引入三层决策模型：**RagMode → RagDecision → RagExecution**

```
用户创建文章 → 传入 ragMode (OFF/ON/AUTO) + kbId(可选)
  ↓
ArticleServiceImpl.createArticleTask()
  ├─ ragMode 兼容 ragEnabled 逻辑（见 §3.2）
  ├─ 保存 article.ragMode 到数据库
  └─ 触发异步阶段
  ↓
ArticleAsyncService.executePhase{N}()
  ├─ 构建阶段 query（见 §1.3）
  ├─ 调用 RagDecisionService.decide(article, query, phase)
  │   ├─ OFF → useRag=false
  │   ├─ ON  → useRag=true, kbId=article.kbId
  │   └─ AUTO → KnowledgeBaseRouterService.route(userId, query, topK)
  │       ├─ matched=true  → useRag=true, kbId=routeResult.kbId
  │       └─ matched=false → useRag=false（降级普通生成）
  └─ useRag=true → RagService.buildRagContextByUserId(kbId, query, topK, userId)
     useRag=false → state.ragContext = ""
```

### 1.3 不同阶段查询文本构造规则

| 阶段 | 用途 | query 构成 | 最大长度 |
|------|------|-----------|---------|
| 阶段1 | 标题生成 | `topic` | 3000 |
| 阶段2 | 大纲生成 | `topic + "\n" + mainTitle + "\n" + subTitle` | 3000 |
| 阶段3 | 正文生成 | `topic + "\n" + mainTitle + "\n" + subTitle + "\n" + outlineJson` | 3000 |

**设计理由**：阶段越往后，可用上下文越丰富，检索精度越高。

---

## 二、技术选型与依赖说明

| 组件 | 选型 | 原因 |
|------|------|------|
| 枚举 | 纯 Java enum（项目模式） | 保持与 `ArticleStatusEnum`、`KnowledgeBaseStatusEnum` 等一致 |
| 知识库路由 | `KnowledgeBaseRouterService` + `EmbeddingUtils` | 复用已有文本相似度工具，不引入外部向量数据库 |
| 决策服务 | `RagDecisionService`（新建） | 集中 OFF/ON/AUTO 三种模式的决策逻辑，避免散落在 AsyncService 中 |
| 路由结果 DTO | `KnowledgeBaseRouteResult` | 封装路由结论（matched/kbId/score/chunks），清晰的数据契约 |
| 数据库 | MyBatis-Flex `ServiceImpl` + `QueryWrapper` | 项目标准，不引入 MyBatis-Plus |
| 文本相似度 | `EmbeddingUtils.calculateTextSimilarity()` | 已有实现，基于关键词匹配 + 字面覆盖率，阈值 0.25 |

**不引入的组件**：Elasticsearch、Milvus、Pinecone、真实 Embedding 模型。

---

## 三、开发步骤规划（按优先级，逐步执行）

### 第 1 步：新增 RagModeEnum

**文件**：`model/enums/RagModeEnum.java`（新建）

```
RagModeEnum:
  OFF("OFF", "不使用RAG")
  ON("ON", "强制使用指定知识库")
  AUTO("AUTO", "自动选择知识库")

方法：
  - getByValue(String value) → RagModeEnum（遍历 values()，匹配 value 字段，未匹配返回 null）
  - value、description 使用 @Getter
```

**验收**：编译通过（JDK 25 除外），枚举值能正确通过 `getByValue("AUTO")` 返回对应实例。

---

### 第 2 步：修改 ArticleCreateRequest（新增 ragMode）

**文件**：`model/dto/article/ArticleCreateRequest.java`（修改）

```java
// 新增字段（保留 ragEnabled 和 kbId）
private String ragMode;  // "OFF" / "ON" / "AUTO"，为空默认 OFF
```

**注意**：
- 不删除 `ragEnabled` 和 `kbId`
- `ragMode` 为 `String` 类型，与项目其他枚举使用方式一致

**验收**：JSON 反序列化能正确接收 `{"ragMode": "AUTO"}`。

---

### 第 3 步：修改 Article 实体 + 数据库表

#### 3.1 数据库 DDL

**文件**：`sql/rag_init.sql`（追加）

```sql
ALTER TABLE article
    ADD COLUMN ragMode VARCHAR(16) DEFAULT 'OFF' null comment 'RAG模式：OFF/ON/AUTO';
```

#### 3.2 Entity 字段

**文件**：`model/entity/Article.java`（修改）

```java
// 新增字段（保留 kbId 和 ragEnabled）
private String ragMode;  // RAG 模式：OFF / ON / AUTO
```

**注意**：
- 字段名 `ragMode` 对应 DB 列名 `ragMode`（`camelToUnderline = false`）
- `ragEnabled` 仍然保留，兼容旧数据

**验收**：MyBatis-Flex 能正确读写 `ragMode` 列。

---

### 第 4 步：修改 ArticleState（新增 ragMode）

**文件**：`model/dto/article/ArticleState.java`（修改）

```java
// 新增字段（保留 ragEnabled、kbId、ragContext）
private String ragMode;  // RAG 模式：OFF / ON / AUTO
```

**验收**：状态对象能正确携带 `ragMode` 贯穿整个生成流程。

---

### 第 5 步：新增 KnowledgeBaseRouteResult

**文件**：`rag/KnowledgeBaseRouteResult.java`（新建）

```java
@Data
public class KnowledgeBaseRouteResult implements Serializable {
    private Boolean matched;        // 是否找到合适知识库
    private String kbId;            // 最相关知识库 ID
    private String kbName;          // 知识库名称
    private Double score;           // 最高相关度分数（0~1）
    private String reason;          // 路由原因说明
    private List<RetrievedChunk> chunks;  // 检索出的 topK 切片
}
```

**验收**：能正确封装路由结果，字段完整。

---

### 第 6 步：新增 KnowledgeBaseRouterService

#### 6.1 接口

**文件**：`service/KnowledgeBaseRouterService.java`（新建）

```java
public interface KnowledgeBaseRouterService {
    KnowledgeBaseRouteResult route(Long userId, String query, Integer topK);
}
```

#### 6.2 实现

**文件**：`service/impl/KnowledgeBaseRouterServiceImpl.java`（新建）

**依赖注入**：
- `CourseKnowledgeBaseService courseKnowledgeBaseService` — 查询用户所有知识库
- `CourseDocumentChunkService courseDocumentChunkService` — 查询知识库切片
- `RagService ragService` — 调用 `retrieveByUserId()` 获取检索结果
- `EmbeddingUtils embeddingUtils` — 计算相似度

**核心逻辑 `route(userId, query, topK)`**：

```
1. 校验 userId 不为空，query 不为空
2. 设 topK 默认值 5，限制 [1, 10]
3. 查询用户所有知识库：
   QueryWrapper.create()
       .eq("userId", userId)
       .eq("status", KnowledgeBaseStatusEnum.NORMAL.getValue())  // 只查 NORMAL 状态
   → List<CourseKnowledgeBase>
4. 如果列表为空 → 返回 matched=false, reason="用户无可用知识库"
5. 遍历每个知识库：
   a. 查询该 kbId 下的切片：
      QueryWrapper.create()
          .eq("kbId", kb.getKbId())
      → List<CourseDocumentChunk>
   b. 如果切片为空 → skip 该知识库
   c. 计算相似度：
      对每个 chunk，调用 embeddingUtils.calculateTextSimilarity(query, chunk.getContent())
      取所有 chunk 分数的最大值作为该知识库的 score
   d. 打印日志：log.info("知识库评分, kbId={}, kbName={}, chunkCount={}, score={}", ...)
6. 选择 score 最高的知识库
7. 设阈值 minScore = 0.25
8. 如果最高 score < minScore → 返回 matched=false, reason="未找到足够相关的知识库, 最高分=X.XX < 0.25"
9. 如果达标 → 调用 ragService.retrieveByUserId(bestKbId, query, topK, userId) 获取切片
   → 返回 matched=true, kbId, kbName, score, chunks=检索结果, reason="自动选择知识库[xxx], 相关度=X.XX"
```

**重要细节**：
- 步骤 5c 的相似度计算直接对每个 chunk 调用 `EmbeddingUtils`，不通过 `RagService.retrieve`（避免重复检索）
- 步骤 9 再调用 `RagService.retrieveByUserId` 获取正式检索结果

**阈值说明**：
- 0.25 基于 `EmbeddingUtils` 的算法特性（关键词命中率0.6 + 字面覆盖率0.4），经测试代表一定的主题相关性
- 如果未来发现阈值偏高或偏低，只需修改常量，不影响其他逻辑

**验收**：
- 有相关知识库时返回 `matched=true`
- 无相关知识库时返回 `matched=false`，不抛异常
- 日志能清晰看到每个知识库的评分

---

### 第 7 步：新增 RagDecisionService

**文件**：`service/RagDecisionService.java`（新建，不需要接口，直接 `@Service`）

**方法签名**：
```java
public RagDecision decide(Article article, String query, String phase)
```

#### 7.1 内部分析类 RagDecision

作为 `RagDecisionService` 的内部静态类：

```java
@Data
public static class RagDecision {
    private boolean useRag;          // 是否使用 RAG
    private String kbId;             // 实际使用的知识库 ID
    private String query;            // 检索查询文本
    private Integer topK;            // topK
    private String reason;           // 决策原因
    private KnowledgeBaseRouteResult routeResult;  // AUTO 模式的路由结果（可为 null）
}
```

#### 7.2 决策逻辑

```
输入：article（含 ragMode/ragEnabled/kbId/userId）, query, phase

1. 解析 ragMode（兼容逻辑见 §3.2）：
   String effectiveRagMode = resolveRagMode(article);
   // 优先 ragMode，其次 ragEnabled，最后默认 OFF

2. 根据 effectiveRagMode 分支：

   case "OFF":
       return new RagDecision(false, null, query, 5, "RAG模式为OFF", null);

   case "ON":
       // 正常流程已在 ArticleServiceImpl 创建时校验 kbId 不为空，此处为防御性代码
       if (article.getKbId() == null || article.getKbId().trim().isEmpty()) {
           log.warn("ON模式但kbId为空（不应出现，创建时已校验），降级, taskId={}", article.getTaskId());
           return new RagDecision(false, null, query, 5, "ON模式但kbId为空，降级为普通生成", null);
       }
       return new RagDecision(true, article.getKbId(), query, 5, "强制使用指定知识库", null);

   case "AUTO":
       KnowledgeBaseRouteResult routeResult = 
           knowledgeBaseRouterService.route(article.getUserId(), query, 5);
       if (routeResult.getMatched()) {
           return new RagDecision(true, routeResult.getKbId(), query, 5, 
               "AUTO模式：匹配到知识库[" + routeResult.getKbName() + "]", routeResult);
       } else {
           return new RagDecision(false, null, query, 5, 
               "AUTO模式：" + routeResult.getReason(), routeResult);
       }

3. 打印决策日志：
   log.info("RAG决策结果, taskId={}, phase={}, ragMode={}, useRag={}, kbId={}, reason={}",
       article.getTaskId(), phase, effectiveRagMode, decision.isUseRag(), 
       decision.getKbId(), decision.getReason());
```

#### 7.3 ragMode 兼容 ragEnabled 逻辑（resolveRagMode）

```
resolveRagMode(Article article):
    1. 取 article.getRagMode()，trim 后非空 → 直接返回（最高优先级）
    2. ragMode 为空，检查 ragEnabled：
       a. article.getRagEnabled() != null && article.getRagEnabled() == 1 → 返回 "ON"（向后兼容）
       b. 否则 → 返回 "OFF"
```

**兼容矩阵**：

| ragMode | ragEnabled | kbId | 有效模式 | 行为 |
|---------|-----------|------|---------|------|
| null | null/false | null | OFF | 普通生成 |
| null | true | "xxx" | ON | 使用指定 kbId |
| "OFF" | 任意 | 任意 | OFF | 普通生成（忽略 ragEnabled） |
| "ON" | 任意 | "xxx" | ON | 使用指定 kbId |
| "ON" | 任意 | null/空 | ON→降级OFF | kbId 为空，降级 |
| "AUTO" | 任意 | 任意 | AUTO | 自动路由 |
| "AUTO" | 任意 | null | AUTO | 自动路由（正常） |

**验收**：
- `ragMode` 为空且 `ragEnabled=true` 时，行为与改进前一致
- `ragMode="OFF"` 时，即使 `ragEnabled=true` 也不使用 RAG
- `ragMode="AUTO"` 时，自动路由

---

### 第 8 步：修改 ArticleServiceImpl（处理 ragMode）

**文件**：`service/impl/ArticleServiceImpl.java`（修改）

**修改点**：`createArticleTask()` 方法

```
原逻辑：
  boolean isRagEnabled = ragEnabled != null && ragEnabled;
  if (isRagEnabled && (kbId == null || kbId.trim().isEmpty())) {
      throw new BusinessException(..., "启用 RAG 时知识库ID不能为空");
  }
  article.setRagEnabled(isRagEnabled ? 1 : 0);
  article.setKbId(isRagEnabled ? kbId : null);

新逻辑：
  1. 解析 ragMode：
     String effectiveRagMode = ...;  // 同上兼容逻辑
  2. 保存：
     article.setRagMode(effectiveRagMode);  // 新增
     article.setRagEnabled("ON".equals(effectiveRagMode) || "AUTO".equals(effectiveRagMode) ? 1 : 0);
     article.setKbId("ON".equals(effectiveRagMode) ? kbId : null);  
     // AUTO 模式不一定有 kbId，设为 null，后续由 Router 填写
  3. 参数校验：
     if ("ON".equals(effectiveRagMode) && (kbId == null || kbId.trim().isEmpty())) {
         throw new BusinessException(..., "RAG 模式为 ON 时必须指定知识库ID");
     }
     // AUTO 模式不要求 kbId
```

**注意**：
- `ragEnabled` 仍然写入数据库，保持兼容性（旧代码可能依赖 `article.getRagEnabled() == 1` 判断）
- `kbId` 在 AUTO 模式下初始为 null，Router 选中后可选择回写 article 表（但非必须）

**验收**：ON 模式不传 kbId 时报错；AUTO 模式不传 kbId 正常创建。

---

### 第 9 步：重写 ArticleAsyncService.enrichStateWithRagContext()

**文件**：`service/ArticleAsyncService.java`（修改）

**当前方法**：
```java
private void enrichStateWithRagContext(ArticleState state, Article article, String query)
```

**改造后**：

```java
private void enrichStateWithRagContext(ArticleState state, Article article, String query, String phase) {
    // 1. 初始化
    state.setRagMode(article.getRagMode());
    state.setRagEnabled(article.getRagEnabled() != null && article.getRagEnabled() == 1);
    state.setKbId(article.getKbId());
    state.setRagContext("");

    // 2. 调用决策服务
    RagDecisionService.RagDecision decision;
    try {
        decision = ragDecisionService.decide(article, query, phase);
    } catch (Exception e) {
        log.error("RAG决策异常, taskId={}, phase={}, 降级为普通生成", article.getTaskId(), phase, e);
        state.setRagContext("");
        return;
    }

    // 3. 根据决策执行
    if (!decision.isUseRag()) {
        log.info("RAG未启用, taskId={}, phase={}, reason={}", article.getTaskId(), phase, decision.getReason());
        return;  // state.ragContext 保持 ""
    }

    // 4. useRag=true，检索知识库
    try {
        String ragContext = ragService.buildRagContextByUserId(
                decision.getKbId(), decision.getQuery(), decision.getTopK(), article.getUserId());
        if (ragContext != null && !ragContext.isEmpty()) {
            state.setRagContext(ragContext);
            state.setKbId(decision.getKbId());  // AUTO 模式写入实际选中的 kbId
            log.info("RAG上下文已注入, taskId={}, phase={}, kbId={}, contextLength={}",
                    article.getTaskId(), phase, decision.getKbId(), ragContext.length());
        } else {
            log.info("RAG检索无结果, taskId={}, phase={}, kbId={}", 
                    article.getTaskId(), phase, decision.getKbId());
        }
    } catch (Exception e) {
        log.warn("RAG检索异常，降级为空上下文, taskId={}, phase={}, kbId={}",
                article.getTaskId(), phase, decision.getKbId(), e);
        state.setRagContext("");
    }
}
```

**三个调用点修改**：

| 阶段 | 原调用 | 新调用 | query 来源 |
|------|--------|--------|-----------|
| `executePhase1` 第80行 | `enrichStateWithRagContext(state, article, topic)` | `enrichStateWithRagContext(state, article, buildPhaseQuery(1, topic, null, null, null), "TITLE_GENERATING")` | topic |
| `executePhase2` 第147行 | `enrichStateWithRagContext(state, article, article.getTopic())` | `enrichStateWithRagContext(state, article, buildPhaseQuery(2, article.getTopic(), article.getMainTitle(), article.getSubTitle(), null), "OUTLINE_GENERATING")` | topic+标题 |
| `executePhase3` 第239行 | `enrichStateWithRagContext(state, article, article.getTopic())` | `enrichStateWithRagContext(state, article, buildPhaseQuery(3, article.getTopic(), article.getMainTitle(), article.getSubTitle(), article.getOutline()), "CONTENT_GENERATING")` | topic+标题+大纲 |

**新增辅助方法** `buildPhaseQuery`：

```java
private String buildPhaseQuery(int phase, String topic, String mainTitle, String subTitle, String outline) {
    StringBuilder sb = new StringBuilder();
    if (topic != null) sb.append(topic);
    if (mainTitle != null && !mainTitle.isEmpty()) sb.append("\n").append(mainTitle);
    if (subTitle != null && !subTitle.isEmpty()) sb.append("\n").append(subTitle);
    if (outline != null && !outline.isEmpty()) sb.append("\n").append(outline);
    String query = sb.toString();
    if (query.length() > 3000) {
        query = query.substring(0, 3000);
    }
    return query;
}
```

**验收**：
- 各阶段的 query 长度随阶段递增
- query 最长不超过 3000 字符
- filter null 和空字符串

---

### 第 10 步：修改 ArticleController（传递 ragMode）

**文件**：`controller/ArticleController.java`（修改）

**修改点**：`createArticle` 方法

```java
// 原调用
articleService.createArticleTaskWithQuotaCheck(
    request.getTopic(), request.getStyle(), request.getEnabledImageMethods(),
    request.getRagEnabled(), request.getKbId(), loginUser);

// 新调用：保持不变，因为 ArticleServiceImpl.createArticleTask() 
// 内部读取 request 的 ragMode，无需改 Controller 签名
// 但如果 createArticleTask 方法签名显式接收 ragMode 参数，则需要：
articleService.createArticleTaskWithQuotaCheck(
    request.getTopic(), request.getStyle(), request.getEnabledImageMethods(),
    request.getRagEnabled(), request.getKbId(), request.getRagMode(), loginUser);
```

**具体方案**：给 `ArticleService.createArticleTask` 和 `createArticleTaskWithQuotaCheck` 加上 `String ragMode` 参数，Controller 传入 `request.getRagMode()`。

---

### 第 11 步：修改 ArticleService 接口签名

**文件**：`service/ArticleService.java`（修改）

两个方法签名新增 `String ragMode` 参数：
```java
String createArticleTask(String topic, String style, List<String> enabledImageMethods,
        Boolean ragEnabled, String kbId, String ragMode, User loginUser);

String createArticleTaskWithQuotaCheck(String topic, String style, List<String> enabledImageMethods,
        Boolean ragEnabled, String kbId, String ragMode, User loginUser);
```

---

### 第 12 步：新增 RagDecisionService 依赖注入

**文件**：`service/ArticleAsyncService.java`（修改）

新增：
```java
@Resource
private RagDecisionService ragDecisionService;
```

---

## 四、文件变更清单

### 新增文件（6个）

| # | 文件路径 | 说明 |
|---|---------|------|
| 1 | `model/enums/RagModeEnum.java` | RAG 模式枚举：OFF/ON/AUTO |
| 2 | `rag/KnowledgeBaseRouteResult.java` | 知识库路由结果 DTO |
| 3 | `service/KnowledgeBaseRouterService.java` | 知识库路由服务接口 |
| 4 | `service/impl/KnowledgeBaseRouterServiceImpl.java` | 知识库路由服务实现 |
| 5 | `service/RagDecisionService.java` | RAG 决策服务 |
| 6 | `docs/rag改进v1版本.md` | 本方案文档 |

### 修改文件（8个）

| # | 文件路径 | 改动内容 |
|---|---------|---------|
| 1 | `model/dto/article/ArticleCreateRequest.java` | 新增 `ragMode` 字段 |
| 2 | `model/entity/Article.java` | 新增 `ragMode` 字段 |
| 3 | `model/dto/article/ArticleState.java` | 新增 `ragMode` 字段 |
| 4 | `sql/rag_init.sql` | 新增 `ALTER TABLE article ADD COLUMN ragMode` |
| 5 | `service/impl/ArticleServiceImpl.java` | `createArticleTask()` 中处理 ragMode + 兼容逻辑 |
| 6 | `service/ArticleService.java` | 方法签名新增 `ragMode` 参数 |
| 7 | `controller/ArticleController.java` | 传递 `ragMode` 参数 |
| 8 | `service/ArticleAsyncService.java` | 重写 `enrichStateWithRagContext()`，注入 `RagDecisionService`，新增 `buildPhaseQuery()` |

---

## 五、验收要点

### 5.1 功能验收

- [ ] **OFF 模式**：不调用 RAG，文章正常生成，日志显示 `RAG模式为OFF`
- [ ] **ON 模式**：强制使用指定 kbId，效果与旧 `ragEnabled=true + kbId` 一致
- [ ] **ON 模式 kbId 为空**：降级为普通生成，不报错，日志显示降级原因
- [ ] **AUTO 模式有相关知识库**：自动选择最佳知识库，日志显示 `selectedKbId` 和 `score`
- [ ] **AUTO 模式无相关知识库**：降级为普通生成，不报错，日志显示路由原因
- [ ] **AUTO 模式无可用知识库**：返回 `matched=false`，降级，不报错
- [ ] **向后兼容**：只传 `ragEnabled=true + kbId`（不传 ragMode），行为与改进前一致
- [ ] **ragMode 优先级**：`ragMode="OFF"` 且 `ragEnabled=true`，不调用 RAG

### 5.2 降级验收

- [ ] `knowledgeBaseRouterService.route()` 中任一知识库无切片 → skip，继续遍历
- [ ] `enrichStateWithRagContext()` 中 `ragDecisionService.decide()` 抛异常 → 降级空上下文
- [ ] `enrichStateWithRagContext()` 中 `ragService.buildRagContextByUserId()` 抛异常 → 降级空上下文
- [ ] 所有降级路径都不能中断文章生成

### 5.3 日志验收

每条日志必须包含 `taskId` 和 `phase`，便于追踪：

```
# RAG 决策
RAG决策结果, taskId=xxx, phase=TITLE_GENERATING, ragMode=AUTO, useRag=true, kbId=yyy, reason=AUTO模式：匹配到知识库[恋爱FAQ知识库], 相关度=0.62

# 路由评分（每条知识库一条）
知识库评分, kbId=yyy, kbName=恋爱FAQ知识库, chunkCount=50, score=0.62
知识库评分, kbId=zzz, kbName=Java基础知识库, chunkCount=30, score=0.12

# 路由结果
AUTO路由结果, taskId=xxx, phase=TITLE_GENERATING, queryLength=15, matched=true, selectedKbId=yyy, score=0.62, reason=自动选择知识库[恋爱FAQ知识库], 相关度=0.62

# 上下文注入
RAG上下文已注入, taskId=xxx, phase=TITLE_GENERATING, kbId=yyy, contextLength=1234

# 降级
RAG未启用, taskId=xxx, phase=TITLE_GENERATING, reason=未找到足够相关的知识库, 最高分=0.12 < 0.25
```

### 5.4 不可破坏项验收

- [ ] 普通文章生成（不传任何 RAG 参数）不受影响
- [ ] SSE 流式输出不受影响
- [ ] 配图流程不受影响
- [ ] 文档上传/解析流程不受影响
- [ ] 旧 `ragEnabled=true + kbId` 调用方式不受影响
- [ ] 不引入真实向量数据库或 Embedding 模型

### 5.5 测试用例

**测试1：OFF 模式**
```json
{ "topic": "女生回复消息很慢应该怎么办", "style": "education", "ragMode": "OFF" }
```
预期：不调用 RAG，文章正常生成。

**测试2：AUTO 模式，有相关知识库**
```json
{ "topic": "女生回复消息很慢应该怎么办", "style": "education", "ragMode": "AUTO" }
```
前提：已创建"恋爱 FAQ 知识库"并上传相关内容。
预期：系统自动选择该知识库，标题/大纲/正文结合知识库资料。

**测试3：AUTO 模式，无相关知识库**
```json
{ "topic": "Java 分布式锁怎么实现", "style": "education", "ragMode": "AUTO" }
```
前提：用户只有"恋爱 FAQ 知识库"。
预期：路由分数低于阈值，useRag=false，普通生成，不混入恋爱知识。

**测试4：ON 模式兼容旧逻辑**
```json
{ "topic": "女生回复消息很慢应该怎么办", "style": "education", "ragMode": "ON", "kbId": "<恋爱FAQ kbId>" }
```
预期：强制使用指定 kbId，与旧 `ragEnabled=true + kbId` 效果一致。

**测试5：旧参数兼容**
```json
{ "topic": "xxx", "style": "education", "ragEnabled": true, "kbId": "<kbId>" }
```
预期：不传 `ragMode`，行为与改进前一致。

---

## 六、架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                    ArticleController                             │
│  POST /article/create { topic, style, ragMode, ragEnabled, kbId }│
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│              ArticleServiceImpl.createArticleTask()              │
│  ├─ resolveRagMode(): OFF / ON / AUTO                          │
│  ├─ 校验: ON 模式必须传 kbId                                     │
│  ├─ 保存 article.ragMode, ragEnabled, kbId                      │
│  └─ 触发异步阶段                                                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│            ArticleAsyncService.executePhase{N}()                 │
│  ├─ buildPhaseQuery(phase, topic, title, subTitle, outline)     │
│  ├─ enrichStateWithRagContext(state, article, query, phase)     │
│  │   ├─ RagDecisionService.decide(article, query, phase)       │
│  │   │   ├─ OFF  → useRag=false                                │
│  │   │   ├─ ON   → useRag=true, kbId=article.kbId             │
│  │   │   └─ AUTO → KnowledgeBaseRouterService.route(...)       │
│  │   │       ├─ 遍历用户知识库，计算最高分                       │
│  │   │       ├─ score >= 0.25 → matched=true                    │
│  │   │       └─ score < 0.25  → matched=false                  │
│  │   └─ useRag=true → RagService.buildRagContextByUserId()     │
│  └─ state.ragContext 注入 Agent Prompt                          │
└─────────────────────────────────────────────────────────────────┘
```

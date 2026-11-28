package cn.lincoln.aiplatform.platform.retrieval;

import java.time.Instant;

/**
 * 检索引擎的运行时开关（表 {@code pf_retrieval_settings}，单行 · 迁移 {@code V100}）。
 *
 * <h3>★ 为什么这不等于 {@link RerankProperties}</h3>
 *
 * <p>{@code RerankProperties} 是 {@code application.yml} 的**绑定结果**（含 {@code modelPath} / {@code
 * tokenizerPath} 这类"部署时才有意义"的格子），构造即校验、不可变。它是**迁移前的兜底**与 **测试构造的来源**。
 *
 * <p>本类型只装**运行期允许改**的那几格，值来自库。判据：一个格子能不能进这里，看"改它需不需要动 部署" —— 需要（模型路径、分词器路径）就留在
 * properties；不需要（开关、窗口、批大小）就进库。
 *
 * <h3>★ 为什么每一格都带着"它动了会怎样"</h3>
 *
 * <p>配置页上如果没有读数，它就是一个**能把自己调坏的旋钮**。所以本类型的 javadoc 把实测出来的 四组读数写在各字段上（来源 `ROADMAP
 * §11.45.2`），页面直接引用同一批数字 —— 两边写一份会漂。
 *
 * @param queryUnderstandingEnabled 查询理解（问题改写）总开关。关掉 = 每轮少一次 LLM 调用；但所有 B1/B3 读数会变成"没改写"的口径（`#342`
 *     记过：这台机器上曾 10 条里 8 条解析失败）
 * @param queryUnderstandingRetry 解析失败是否重试一次。实测：关掉省 **1.4s** 中位延迟、掉 **1.1 点** B3 ⇒ 是取舍不是白拿
 * @param rerankEnabled cross-encoder 重排开关。关掉省 **4.74s**，B3 −5 点（20 条子集口径）
 * @param rerankTopK 重排窗口（送进 cross-encoder 的条数）。窗口 10 → 20：B1 **+5 点**、B3 **不动**、耗时 **4.74s →
 *     ~9.5s**
 * @param rerankForwardBatch 前向批大小，**0 = 整个窗口一次前向**。批内按最长补齐 ⇒ 快慢取决于 长度分布，写死任何值都是猜
 * @param rerankMaxLength 重排的截断长度（token）。512→256 实测重排 **−33%**、合计
 *     **−16%**（2026-11-28）；截断越短长条文的尾巴越容易被砍掉 ⇒ 改它必须重跑语料对照 B3
 * @param listwiseEnabled LLM listwise 仲裁（默认关：**3.12s/条**，会破 B4 的 10s 单跳线）
 * @param listwiseTopK listwise 参与的条数（只在 {@code listwiseEnabled} 为真时有意义）
 * @param updatedAt 上次修改时刻（服务端写；从未改过则为 {@code null}）
 */
public record RetrievalEngineSettings(
    boolean queryUnderstandingEnabled,
    boolean queryUnderstandingRetry,
    boolean rerankEnabled,
    int rerankTopK,
    int rerankForwardBatch,
    int rerankMaxLength,
    boolean listwiseEnabled,
    int listwiseTopK,
    Instant updatedAt) {

  /** 窗口的上限：与迁移里的 check 约束同源，应用层先挡一次。 */
  public static final int MAX_TOP_K = 50;

  /** 批大小的上限（0 合法，见 {@code rerankForwardBatch} 的说明）。 */
  public static final int MAX_FORWARD_BATCH = 100;

  /** 截断长度的下限：32 个 token 大致只够一个短标题，再短就等于「把整段话截成一个词」， 重排分数会退化成噪声 —— 那种值不应该能被填进去。与迁移的 `check` 约束同源。 */
  public static final int MIN_MAX_LENGTH = 32;

  /** 截断长度的上限（bge-reranker-base 的可用上限附近，再大只会把单条延迟推到几十秒）。 */
  public static final int MAX_MAX_LENGTH = 2048;

  /** listwise 条数的上限。 */
  public static final int MAX_LISTWISE_TOP_K = 20;

  /**
   * 校验并归一（构造器里不做，因为 record 的 compact 构造器抛异常会让"读库"也走这条路径）。
   *
   * @return 校验通过的值
   * @throws IllegalArgumentException 任一格越界
   */
  public RetrievalEngineSettings checked() {
    if (rerankTopK < 1 || rerankTopK > MAX_TOP_K) {
      throw new IllegalArgumentException("重排窗口要在 1～" + MAX_TOP_K + " 之间，现在写的是 " + rerankTopK);
    }
    if (rerankForwardBatch < 0 || rerankForwardBatch > MAX_FORWARD_BATCH) {
      throw new IllegalArgumentException(
          "前向批大小要在 0～" + MAX_FORWARD_BATCH + " 之间（0 = 整个窗口一次前向），现在写的是 " + rerankForwardBatch);
    }
    if (rerankMaxLength < MIN_MAX_LENGTH || rerankMaxLength > MAX_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "重排截断长度要在 "
              + MIN_MAX_LENGTH
              + "～"
              + MAX_MAX_LENGTH
              + " 个 token 之间，现在写的是 "
              + rerankMaxLength);
    }
    if (listwiseTopK < 1 || listwiseTopK > MAX_LISTWISE_TOP_K) {
      throw new IllegalArgumentException(
          "listwise 条数要在 1～" + MAX_LISTWISE_TOP_K + " 之间，现在写的是 " + listwiseTopK);
    }
    return this;
  }
}

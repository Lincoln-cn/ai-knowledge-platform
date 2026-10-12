package cn.lincoln.aiplatform.retrieval.domain;

/**
 * 多跳的"判定"产出（{@code 10 §3.4.3}）。
 *
 * <p>模型这一轮要回答两件事：<strong>够不够</strong>（{@code sufficient}）与<strong>不够的话下一轮问什么</strong> （{@code
 * nextQuery}）。形状就这两个字段 —— 多一个字段就多一种"模型给了但我不用"的静默分歧。
 *
 * <h3>★ JSON 由官方转换器读，领域侧只管"什么算可用"</h3>
 *
 * <p>拿模型输出的那一步走 {@code ChatPort#completeAs}（适配器里是官方 {@code BeanOutputConverter}：格式说明由它生成、 响应由它解析）——
 * 见 {@code 11 §2.7} 的口径："实现结构化输出时先看官方，不要又写一层"。 剥 markdown 围栏、忽略前后废话这些容错
 * <strong>不在这里</strong>：官方转换器已经在做，自研一份只多一处要维护的猜测。
 *
 * <p>这里留下的三件事都是<strong>转换器不该管、也管不了</strong>的判定规则：
 *
 * <ul>
 *   <li>★ {@link Payload} 用包装类型 {@code Boolean}：<strong>字段缺失 与 {@code false} 必须分得开</strong>。 模型漏掉
 *       {@code sufficient} 时，读成 {@code false} 会让我们带着一个无意义的查询再检一轮，读成 {@code true} 会让多跳永远不触发 ——
 *       两种都静默，所以只认"确实给了 boolean"，否则就是判定不可用。
 *   <li>{@code nextQuery} 超过 {@link #MAX_QUERY_LENGTH} ⇒
 *       <strong>截断，不是整次作废</strong>：判定的价值在"往哪个方向再问一次"， 截断仍保住了方向。
 *   <li>{@code nextQuery} 全空白 ⇒ 归 {@code null}（"模型想不出查询了"）⇒ 调用方停，而不是硬编一条查询。
 * </ul>
 *
 * @param sufficient 模型认为已有命中足以回答
 * @param nextQuery 下一轮要问的查询；{@code sufficient=false} 且它为空 = "模型没想法了" ⇒ 停止（不再硬编）
 * @param failure 非空表示这次判定不可用（不可达 / 超时 / 解析不出来）
 */
public record HopDecision(boolean sufficient, String nextQuery, String failure) {

  /** 单条下一轮查询的长度上限 —— 与查询理解的 {@code MAX_QUERY_LENGTH} 同口径（长查询在全文路会退化）。 */
  public static final int MAX_QUERY_LENGTH = 60;

  /**
   * 官方转换器按这个形状生成 schema 并解析。
   *
   * <p>★ 它是 {@code Boolean} 而不是 {@code boolean}：字段缺失必须能表达出来（见类注释第一条）。 与 {@link HopDecision}
   * 分开的另一个理由：判定的"产出形状"由模型决定，而"算不算可用"由我们决定，两者的变化原因不同。
   */
  public record Payload(Boolean sufficient, String nextQuery) {}

  public boolean usable() {
    return failure == null;
  }

  /** 有可用的下一轮查询（判定可用、且模型确实给出了查询）。 */
  public boolean hasNextQuery() {
    return usable() && !sufficient && nextQuery != null && !nextQuery.isBlank();
  }

  /**
   * 把官方转换器的产出变成一次判定 —— 字段缺失 / 空产出都算<strong>不可用</strong>，不猜默认值。
   *
   * @param payload 转换器产出；{@code null} 也接受（同一族失败，少一处 {@code null} 判断在调用方）
   */
  public static HopDecision from(Payload payload) {
    if (payload == null) {
      return failure("模型没有产出判定");
    }
    if (payload.sufficient() == null) {
      return failure("判定里没有 boolean 的 sufficient 字段");
    }
    return new HopDecision(payload.sufficient(), normalizeQuery(payload.nextQuery()), null);
  }

  /** 判定不可用（不可达 / 超时 / 解析失败）—— 调用方据此停止迭代并用已有结果继续。 */
  public static HopDecision failure(String reason) {
    return new HopDecision(false, null, reason);
  }

  private static String normalizeQuery(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= MAX_QUERY_LENGTH ? trimmed : trimmed.substring(0, MAX_QUERY_LENGTH);
  }
}

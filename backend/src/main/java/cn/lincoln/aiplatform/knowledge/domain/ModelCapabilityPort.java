package cn.lincoln.aiplatform.knowledge.domain;

import java.util.UUID;

/**
 * 模型能力查询端口（第 7 周 · 0.2.2 批 2b）。
 *
 * <p>★ 为什么不直接用 {@code modelcatalog} 的 {@code ModelService}：Spring Modulith 的模块边界 把 {@code
 * modelcatalog.application} 视为非公开 API（{@code ModuleBoundaryTest} 会拦）。 端口放 {@code
 * knowledge.domain}，实现放 {@code knowledge.infrastructure}（JDBC 直查 {@code mc_model} 表）—— 与 {@code
 * AccessScopePort} 同一条判断。
 *
 * <p>★ 用途：解析向量模型的 {@code contextWindow} 作为 {@code maxChars} 的近似值 （把模型窗口数当字数上限，近似口径）。
 */
public interface ModelCapabilityPort {

  /**
   * 查模型的上下文窗口（字符上限近似值）。
   *
   * @param modelId 模型 id
   * @return 上下文窗口；模型不存在或无此字段时返回 null
   */
  Integer contextWindow(UUID modelId);

  /**
   * 取向量模型的**单段字符预算**（`#172`）。
   *
   * <p>★★ 为什么必须单开一个方法：{@link #contextWindow} 对**向量模型恒为 null** （`ai_model` 的表注释写死"对话模型：上下文窗口。向量模型恒为
   * null"）， 所以"这个向量模型一次能安全吃多少**字**"原本**没有任何列承载**。
   *
   * <p>后果是一次真实的静默失效：{@code MaxCharsResolver} 一直在问那个永远为 null 的列 ⇒ 「本地 ONNX 必须压到 400
   * 字」这条口径**从未生效过**，一直是配置在起作用。
   *
   * @param modelId 模型 id
   * @return 字符预算（单位**字**，与 {@code SEGMENT_MAX_CHARS} 同口径）； 模型不存在 / 未登记 ⇒ {@code null}（调用方继续往下退，★ 不是
   *     0 —— 0 会让所有分段都超长）
   */
  Integer maxChars(UUID modelId);
}

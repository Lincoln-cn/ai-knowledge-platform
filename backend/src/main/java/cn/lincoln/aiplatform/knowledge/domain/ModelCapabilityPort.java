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
}

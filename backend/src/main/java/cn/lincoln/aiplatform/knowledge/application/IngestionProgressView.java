package cn.lincoln.aiplatform.knowledge.application;

import java.util.List;

/**
 * 库级入库进度（契约 {@code IngestionProgress}，{@code frontend/src/api/ingestion.ts}）。
 *
 * <p>★ 它是"库页面顶部进度条"的<strong>唯一数据源</strong>：前端只在 {@code running} 为 true 时轮询， 全部完成即停（{@code 03
 * §3.4}）。所以 {@code running} 的语义必须准 —— 它决定界面要不要继续问。
 *
 * @param kbId 知识库 id
 * @param total 文档总数（不含软删）
 * @param done 已完成（{@code SUCCEEDED}）的文档数
 * @param failed 失败文档数（分段失败 + 向量化失败）
 * @param stages 按阶段计数（状态机器码 → 篇数；0 篇的状态不出现）
 * @param running 是否有<strong>自己会往前走</strong>的任务在跑
 */
public record IngestionProgressView(
    String kbId, int total, int done, int failed, List<Stage> stages, boolean running) {

  /** 一个阶段的计数（契约 {@code stages[]}）。 */
  public record Stage(String status, int count) {}
}

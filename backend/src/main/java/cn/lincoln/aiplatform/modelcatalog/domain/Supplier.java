package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 模型供应商。
 *
 * <p>★ 枚举名刻意用<strong>中文字面量</strong>（{@code 本地推理 | 外部API}），与契约 {@code frontend/src/api/models.ts} 的
 * {@code Supplier} 一字不差 —— 与 {@code UserStatus} 同一条纪律： 库、代码、契约三处同一个字面量，中间不夹
 * Converter，少一层映射就少一处可能对不上的地方。
 *
 * <p>它不只是分类标签：本地推理不需要 {@code apiBase}/{@code apiKey}，外部 API 必须带 —— 这个差异 在落库校验与加载链路里都会用到。
 */
public enum Supplier {
  本地推理,
  外部API
}

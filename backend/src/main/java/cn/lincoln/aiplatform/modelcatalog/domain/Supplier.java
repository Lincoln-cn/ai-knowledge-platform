package cn.lincoln.aiplatform.modelcatalog.domain;

/**
 * 模型供应商。
 *
 * <p>★ 取值用 <strong>SCREAMING_SNAKE_CASE 机器码</strong>（{@code LOCAL_INFERENCE / EXTERNAL_API}）， 标识符 =
 * 线上值 = 库存值三者同名。展示文案（"本地推理 / 外部API"）只出现在前端 label 映射里， <strong>不进标识符、不进线上值、不进库</strong> ——
 * 机器码稳定、可比较、可排序；文案随时可改而不影响契约与数据。
 *
 * <p>它不只是分类标签：本地推理不需要 {@code apiBase}/{@code apiKey}，外部 API 必须带 —— 这个差异 在落库校验与加载链路里都会用到。
 */
public enum Supplier {
  LOCAL_INFERENCE,
  EXTERNAL_API
}

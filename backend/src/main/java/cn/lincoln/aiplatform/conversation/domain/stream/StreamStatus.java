package cn.lincoln.aiplatform.conversation.domain.stream;

/**
 * 流式回答的终态（SSE 契约 {@code frontend/src/api/stream-types.ts} 的 {@code StreamStatus}）。
 *
 * <p>★ 三个取值<strong>逐字对齐契约</strong>：{@code GENERATED} / {@code STOPPED} / {@code FAILED}。
 *
 * <p>★ <strong>不许出现第四个</strong>（例如给 {@code 03 §1.2} 那张示例里的 {@code "status":"DONE"} 也建一个常量）：
 * 那张示例是<strong>说明性</strong>的，而契约里的 {@code StreamStatus} 只有这三个，前端的 {@code onDone} 按它分派
 * UI。多一个取值到了前端就是一个"收到了但认不出"的终态 —— 页面会停在"生成中"。
 *
 * <p>★ 本枚举刻意<strong>不带</strong> {@code isTerminal()} / {@code isFailure()} 这类派生方法：哪一种终止路径映射到哪一个取值，
 * 是 {@link StreamTerminationPath} 的职责（唯一真相源）。在这里再写一遍"哪些算失败"就是第二处口径。
 */
public enum StreamStatus {

  /** 正常生成结束。 */
  GENERATED,

  /** 用户主动停止（{@code 03 §1.5}：停止后<strong>已生成内容必须保留</strong>）。 */
  STOPPED,

  /** 失败（连接断开 / 闲置超时 / 上游报错）。 */
  FAILED
}

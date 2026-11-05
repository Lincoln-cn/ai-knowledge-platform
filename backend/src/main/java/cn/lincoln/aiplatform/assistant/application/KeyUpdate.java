package cn.lincoln.aiplatform.assistant.application;

/**
 * 同一状态键的写入语义（{@code #113} 判据 3）：两个节点写同一个键时怎么办。
 *
 * <p>★ 这是执行引擎"状态怎么留"的最小口径 —— 没有它，两个节点写同名变量的行为就是未定义的。
 */
public enum KeyUpdate {
  /** 后写覆盖前写（最后一个写的人说了算）。 */
  REPLACE,
  /** 后写追加到前写之后（历史都留着）。 */
  APPEND
}

package cn.lincoln.aiplatform.assistant.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流程状态（{@code #113} 判据 3）：按 {@link KeyUpdate} 语义写读。
 *
 * <p>★ 判据 3 的核心：同一状态键被两个节点写 —— {@link KeyUpdate#REPLACE} ⇒ 后写覆盖； {@link KeyUpdate#APPEND} ⇒
 * 追加。两条各有一条能红的测试。
 */
public final class FlowState {

  private final Map<String, String> state = new LinkedHashMap<>();

  /**
   * 按 {@code mode} 语义写一个键。
   *
   * @param key 状态键
   * @param value 值
   * @param mode 写入语义：{@code REPLACE} 覆盖 / {@code APPEND} 追加
   */
  public void write(String key, String value, KeyUpdate mode) {
    if (mode == KeyUpdate.APPEND) {
      state.merge(key, value, (old, appended) -> old + appended);
    } else {
      state.put(key, value);
    }
  }

  /** 读一个键；没有则 {@code null}。 */
  public String read(String key) {
    return state.get(key);
  }

  /**
   * 整个变量表的一份只读快照。
   *
   * <p>★ 给中断用：{@code WorkflowInterrupted} 要在抛出去那一刻把状态存住， 而异常被捕获后原 {@link FlowState}
   * 可能被复用或被回收。返回副本而不是底层 map —— 调用方改了它不该影响执行中的状态。
   */
  public java.util.Map<String, String> asMap() {
    return Map.copyOf(state);
  }
}

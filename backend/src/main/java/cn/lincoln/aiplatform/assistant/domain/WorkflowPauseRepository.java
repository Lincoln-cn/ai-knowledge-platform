package cn.lincoln.aiplatform.assistant.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 中断快照的读写口。
 *
 * <p>★ 为什么这一次写口与读口同一批进：上一批只建了表、写着「等恢复端点落地再加读写」，而那个「等」正是本项目栽过的形状 —— 表在、代码在、真栈跑出来 0
 * 行、一条异常都没有。中断快照如果没人写也没人读，它和不存在没有区别。
 *
 * <p>★ 没有 {@code delete}：恢复之后只填 {@code resumed_at}，行保留。
 */
public interface WorkflowPauseRepository {

  /**
   * 存一份快照。
   *
   * <p>同一 {@code executionId} 重复保存按幂等处理（覆盖）—— 恢复失败重试时同一个执行会被再暂停一次， 而它仍是「同一次执行」。
   */
  void save(WorkflowPause pause);

  /** 按执行 id 取；没有则空。 */
  Optional<WorkflowPause> find(UUID executionId);
}

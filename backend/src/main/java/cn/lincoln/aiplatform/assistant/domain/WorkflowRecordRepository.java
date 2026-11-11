package cn.lincoln.aiplatform.assistant.domain;

import java.util.List;
import java.util.UUID;

/**
 * 编排运行记录的仓储（领域端口）—— 实现见 {@code assistant.infrastructure.WorkflowRecordRepositoryAdapter}。
 *
 * <p>★★ 端口上原先<strong>没有 {@code append} / {@code save}</strong>，理由是"运行记录今天没有写入方"。 2026-11-12 加了
 * {@link #save}，**就是按那条口径加的**：写入方（{@code WorkflowDebugService}，「试用」端点）与这个方法同一批进 —— 不是先开一个口子等人用。
 *
 * <p>★ 那次改动同时推翻了另一半：原来的注释写着"画布调试刻意不写记录"。改写的理由见 {@link #save}。 等执行引擎从"调试"扩到"正式运行"时，同样要**连写入方一起**改这里。
 *
 * <p>★ 顺序只有一个真源：本端口的契约就是"按 {@code started_at} 倒序（新的在前）"。 调用方自己排会埋下"先截断再排序"（只排了第一页，第二页永远排在后面）——
 * 那不是分页错了，是每一页都错。
 */
public interface WorkflowRecordRepository {

  /**
   * 落一行运行记录（{@code #326} 第 2 层）。
   *
   * <p>★ <strong>为什么调试也要写</strong>：不写就无从证明执行引擎真的跑过。这一版的「试用」是本端口的唯一写入方； 若仍按原口径"调试不写"，{@code
   * as_workflow_record} 就永远是空表，而"跑成功了但查不到"与"根本没跑"在界面上读数完全一样。
   *
   * <p>★ <strong>为什么可以写</strong>：调试记录的 {@code conversationId} 为 {@code null} ——
   * 统计口径据此把"试用"与"真问答"分开筛， 不需要另开一张表，也不需要一个 {@code is_debug} 列（那会让列表接口多一种必填的筛选语义）。
   *
   * @return 落库后的记录（其 {@code id} 由调用方生成 —— 生成的时刻要早于执行，失败时也能带着同一个 id 回查）
   */
  WorkflowRecord save(WorkflowRecord record);

  /**
   * 某个应用的一页运行记录，按 {@code startedAt} <strong>倒序</strong>（新的在前）。
   *
   * <p>★ {@code assistantId} 在查询里就是必要条件，不是"查回来再过滤"：那样等于先把别的应用的记录读进内存、再靠调用方记得比一次。
   * 跨应用读取是越权，不该有一个"忘了判断"的实现可能。
   *
   * @param status 只看某一种终态；{@code null} = 不筛（调用方已校验过取值域）
   * @param page 第几页，<strong>从 1 开始</strong>（与契约 {@code PageResult.page} 一致）
   * @param size 每页多少条（调用方已夹到上限）
   */
  List<WorkflowRecord> page(UUID assistantId, String status, int page, int size);

  /**
   * 与 {@link #page} <strong>同一份谓词</strong>下的总条数。
   *
   * <p>★ 必须同谓词：{@code total} 若按"表里的行数"给，用户会看到"共 100 条"却只翻得出 3 条（{@code 08 §4.4.3} 纪律 1）。
   */
  long count(UUID assistantId, String status);
}

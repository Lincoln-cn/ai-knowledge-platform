package cn.lincoln.aiplatform.assistant.domain;

import java.util.List;
import java.util.UUID;

/**
 * 编排运行记录的仓储（领域端口）—— 实现见 {@code assistant.infrastructure.WorkflowRecordRepositoryAdapter}。
 *
 * <p>★★ 端口上<strong>没有 {@code append} / {@code save}</strong>，这是有意的：运行记录今天<b>没有写入方</b>。
 * 唯一的写入者是把图真的跑一遍的执行引擎（{@code 08 §二} / {@code 09 §5.3}，排在 V1.0）， 而画布调试 {@code POST …/workflow/debug}
 * 刻意不写记录。 一个"写了也没人调"的 {@code save} 会让这个端口看起来具备写能力 —— 下一个人就会以为 表里有数据是迟早的事，而不是"必须先把执行引擎做出来"。
 * 等执行引擎落地时，加写方法应当**与它的写入方同一批**进（同 {@link WorkflowVersionRepository} 的 {@code append}）。
 *
 * <p>★ 顺序只有一个真源：本端口的契约就是"按 {@code started_at} 倒序（新的在前）"。 调用方自己排会埋下"先截断再排序"（只排了第一页，第二页永远排在后面）——
 * 那不是分页错了，是每一页都错。
 */
public interface WorkflowRecordRepository {

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

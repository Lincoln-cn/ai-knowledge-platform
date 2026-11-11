package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.WorkflowRecord;
import cn.lincoln.aiplatform.assistant.domain.WorkflowRecordRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * 运行记录的仓储实现（JPA）。
 *
 * <p>★ <strong>排序在这里</strong>：{@link Sort#by(Sort.Direction.DESC, "startedAt")} 是"新的在前"的唯一真源。
 * 领域端口的契约就写着倒序，调用方不排 —— 调用方自己排会让"先分页再排序"成为可能（只排了当前页）。
 *
 * <p>★ {@code status} 为 {@code null} 时走"不筛"的那条查询，而不是拼一个 {@code status is null} 的谓词：
 * 记录的状态永远非空，{@code is null} 会恒假、列表恒空 —— 那种错看起来像"还没有记录"。
 */
@Component
public class WorkflowRecordRepositoryAdapter implements WorkflowRecordRepository {

  /**
   * 列表的唯一排序口径：{@code startedAt} 倒序（新的在前）。
   *
   * <p>★ 抽成常量是为了让"排序"只有一处：契约（{@code WorkflowRecordQuery}）与页面都假设新的在前， 改这里等于改所有调用方的语义。
   */
  private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "startedAt");

  private final JpaWorkflowRecordRepository records;

  public WorkflowRecordRepositoryAdapter(JpaWorkflowRecordRepository records) {
    this.records = records;
  }

  @Override
  public WorkflowRecord save(WorkflowRecord record) {
    // ★ 直接落库、不判重：id 由调用方生成，重复保存同一行是调用方的 bug，静默覆盖会把它变成"数据自己变了"。
    return records.save(record);
  }

  @Override
  public List<WorkflowRecord> page(UUID assistantId, String status, int page, int size) {
    // ★ 端口用 1 起始的页码（契约口径），Spring Data 用 0 起始 —— 换算只在这一行。
    PageRequest request = PageRequest.of(page - 1, size, NEWEST_FIRST);
    return query(assistantId, status, request).getContent();
  }

  @Override
  public long count(UUID assistantId, String status) {
    return status == null
        ? records.countByAssistantId(assistantId)
        : records.countByAssistantIdAndStatus(assistantId, status);
  }

  /** 两条查询共用一处，避免"筛与不筛"在分页边界上各写一套。 */
  private Page<WorkflowRecord> query(UUID assistantId, String status, PageRequest request) {
    return status == null
        ? records.findByAssistantId(assistantId, request)
        : records.findByAssistantIdAndStatus(assistantId, status, request);
  }
}

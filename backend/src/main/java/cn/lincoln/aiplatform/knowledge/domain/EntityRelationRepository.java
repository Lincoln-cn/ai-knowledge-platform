package cn.lincoln.aiplatform.knowledge.domain;

import java.util.List;
import java.util.UUID;

/**
 * 实体关系的写入端口。
 *
 * <p>为什么只有一个写方法、没有读方法：0.6.4 的目标是"关系抽到了要落地"，不是"关系能被查询"。 读口会在真正要消费关系时（见 {@code docs/ROADMAP.md}
 * §11.27.5 的三条触发条件）与消费方同一批进 —— 同 {@code WorkflowRecordRepository} 的 javadoc 所写的口径：加写方法与它的写入方同一批进，
 * 加读方法也应当与它的读取方同一批进。先加一个没人调用的 {@code listOf}， 只会让"关系已经可查"这件事看起来像是完成了。
 *
 * <p>本接口不做去重、不做消歧、不做权限判断 —— 那些在调用方或更上游。
 */
public interface EntityRelationRepository {

  /**
   * 一批关系。重复的（同一 from/to/type）由数据库的唯一约束吞掉，这里不做内存去重： 同一段里「综合部隶属财务资产部」出现两次是同一个事实，唯一键正好表达这一点。
   *
   * @return 实际写入的行数（被唯一约束吞掉的不计入）
   */
  int insertAll(UUID kbId, List<Candidate> relations);

  /**
   * 待写入的一条关系。
   *
   * <p>{@code fromEntity} / {@code toEntity} 是**已经入库**的实体 id，不是名字 ——
   * 两端必须是实体，否则这条边在图里没有端点。调用方负责把名字解析成 id， 解析不出来的就不该走到这里（那说明这条关系的一半不在库里）。
   *
   * <p>{@code documentId} / {@code segmentId} 是取证位置，可以为 null（存量回填那批没有段落时），
   * 但为空就说明这条边**无法核对**，调用方应当尽量给出。
   */
  record Candidate(
      UUID fromEntity,
      UUID toEntity,
      String type,
      UUID documentId,
      UUID segmentId,
      double confidence) {}
}

package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data 的文档仓储。
 *
 * <p>★ 不同时继承 domain 的 {@code DocumentRepository} —— 双继承会让 {@code Pageable} 漏进领域层（与 {@code
 * JpaKnowledgeBaseRepository} 同一条判断）。
 */
public interface JpaDocumentRepository
    extends JpaRepository<Document, UUID>, JpaSpecificationExecutor<Document> {

  /**
   * 按状态分组计数（库级进度聚合用，{@code 03 §3.4}）。
   *
   * <p>★ 在库层分组，而不是"把文档都取回来在内存里数"：后者会在分页/上限处**静默算错** （页大小 100、库里有 340 篇时，进度条永远显示 100/340 那种）。
   *
   * <p>★ 软删文档不计入 —— 它们对用户已经不存在，算进去会让进度条永远到不了头。
   *
   * <p>★★ <strong>2026-10-16 补（{@code #25}）：密级条件与列表侧<u>同一条</u></strong> —— 进度条的分母是"文档总数"，若不按
   * clearance 过滤，低 clearance 的用户会看到"还有 N 份没向量化" 而列表里一份都看不见（{@code #25} 判据 3）。绑定值同样是 {@link
   * cn.lincoln.aiplatform.platform.access.ClassificationLevel#ordinal()}，且 {@code ceiling} 为 {@code
   * null}（取不到身份）时比较结果恒假 ⇒ <strong>fail-closed</strong>（一份都不返回）， 与列表侧 {@code Specification} 的行为逐字一致。
   *
   * @param kbId 知识库 id
   * @param ceiling 密级上限（{@code = maxClassification.ordinal()}）；{@code null} ⇒ 什么都不返回
   */
  @Query(
      "select d.status, count(d) from Document d"
          + " where d.kbId = :kbId and d.deletedAt is null"
          + " and d.classificationRank <= :ceiling"
          + " group by d.status")
  List<Object[]> countGroupedByStatus(@Param("kbId") UUID kbId, @Param("ceiling") Short ceiling);

  /**
   * 找「分段中、但进度早停摆过期」的文档（{@code #303} 超期回收用）。
   *
   * <p>★ 条件是 {@code updatedAt < cutoff} 而不是"创建超过 N 分钟"：解析中的每一页都会回写进度（{@code reportOcrPage}），活着的任务会让
   * {@code updatedAt} 一直往前走 —— <b>停摆的才是可疑的</b>。"创建久"只说明它是份大文件（慢），不说明它卡了。
   *
   * <p>★ 软删文档不回收：用户已经删了它，状态是什么都无所谓，回收只会平白多一条失败记录。
   *
   * @param cutoff 停摆截止时刻（不含等于）
   * @param status 目标状态（调用方传 {@code SEGMENTING}；做成参数是为了不在 JPQL 里写枚举字面量）
   */
  @Query(
      "select d from Document d"
          + " where d.status = :status and d.updatedAt < :cutoff and d.deletedAt is null")
  List<Document> findStaleByStatus(
      @Param("status") cn.lincoln.aiplatform.knowledge.domain.DocumentStatus status,
      @Param("cutoff") java.time.Instant cutoff);

  /**
   * 找「期望解析、但解析任务从未起来」的文档（{@code #304} 超期回收用）。
   *
   * <p>★ 判超期用 {@code parse_expected_at} 而不是 {@code updatedAt}：「待解析」的行没人回写进度，{@code updatedAt} 一动不动
   * —— 按停摆判分不开「刚上传还在等」与「等了很久没人管」；{@code parse_expected_at} 是"应该开始解析的时刻"，超期它就该离开 {@code
   * PENDING_PARSE}。
   *
   * <p>★★ {@code is not null} 是命门：只登记元数据（无字节）的文档永远停在 {@code PENDING_PARSE}
   * 是<strong>正常</strong>的（等字节）， 它们的 {@code parse_expected_at} 为 NULL ——
   * 回收器绝不能把「正常等待」误杀成「任务没起来」（{@code #304} 判据③）。
   *
   * <p>★ 软删文档不回收（同 {@link #findStaleByStatus}）：用户已经删了它，回收只会平白多一条失败记录。
   *
   * @param status 目标状态（调用方传 {@code PENDING_PARSE}；做成参数是为了不在 JPQL 里写枚举字面量）
   * @param cutoff 期望解析时刻的截止（不含等于）
   */
  @Query(
      "select d from Document d"
          + " where d.status = :status and d.parseExpectedAt is not null"
          + " and d.parseExpectedAt < :cutoff and d.deletedAt is null")
  List<Document> findStalePendingParse(
      @Param("status") cn.lincoln.aiplatform.knowledge.domain.DocumentStatus status,
      @Param("cutoff") java.time.Instant cutoff);
}

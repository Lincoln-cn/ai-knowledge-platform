package cn.lincoln.aiplatform.platform.compliance;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 合规设置服务（{@code #83}）：读写 {@code platform_compliance_settings} 那一行。
 *
 * <p>★ 读是全平台的（投诉时限要参与 {@code due_at} 计算），写是管理员的（{@code platform:compliance:manage}）。表里永远恰好一行（主键
 * {@code '1'}，由迁移种入）。
 */
@Service
public class ComplianceSettingsService {

  private static final String ROW_ID = "1";

  /**
   * 缺省反馈时限（天）。<strong>★ 运营口径，不是法条数字</strong>：issue #83 引用的第十五条
   * 只要求「公布处理流程与反馈时限」，未规定具体数字。表缺行时回落到这里，保证 {@code due_at} 永远算得出来（静默没有时限才是事故）。
   */
  static final int DEFAULT_COMPLAINT_FEEDBACK_DAYS = 15;

  private final NamedParameterJdbcTemplate jdbc;

  public ComplianceSettingsService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 读当前合规设置（表缺行时回落到缺省值，不抛错）。 */
  public ComplianceSettings current() {
    return jdbc
        .query(
            """
            select complaint_feedback_days, algorithm_filing_no, security_assessment_conclusion,
                   updated_at, updated_by
            from platform_compliance_settings where id = :id
            """,
            Map.of("id", ROW_ID),
            (rs, n) ->
                new ComplianceSettings(
                    rs.getInt("complaint_feedback_days"),
                    rs.getString("algorithm_filing_no"),
                    rs.getString("security_assessment_conclusion"),
                    rs.getTimestamp("updated_at").toInstant(),
                    (UUID) rs.getObject("updated_by")))
        .stream()
        .findFirst()
        .orElseGet(
            () ->
                new ComplianceSettings(
                    DEFAULT_COMPLAINT_FEEDBACK_DAYS, "", "", Instant.EPOCH, null));
  }

  /**
   * 更新合规设置（合规设置页一次提交整张表单）。
   *
   * <ul>
   *   <li>{@code days}：反馈时限（天），1–90 的整数（运营口径的合理区间；0/负数/离谱值一律 400 —— 公示的时限不能是废值）。
   *   <li>{@code algorithmFilingNo} / {@code securityAssessmentConclusion}：第十七条登记位（留缝）。 空串 = 未登记；
   *       <strong>不校验备案号格式</strong> —— 本版无申报流程，格式真伪无处核对，编一个格式规则反而会把真号挡在门外。
   * </ul>
   *
   * @param updatedBy 操作者（留痕）
   */
  public ComplianceSettings update(
      int days, String algorithmFilingNo, String securityAssessmentConclusion, UUID updatedBy) {
    if (days < 1 || days > 90) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "反馈时限必须是 1–90 之间的天数，实际：" + days);
    }
    String filingNo = algorithmFilingNo == null ? "" : algorithmFilingNo.trim();
    String conclusion =
        securityAssessmentConclusion == null ? "" : securityAssessmentConclusion.trim();
    if (filingNo.length() > 128) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "算法备案号不超过 128 个字符");
    }
    if (conclusion.length() > 512) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "安全评估结论不超过 512 个字符");
    }
    jdbc.update(
        """
        update platform_compliance_settings
           set complaint_feedback_days = :days,
               algorithm_filing_no = :filingNo,
               security_assessment_conclusion = :conclusion,
               updated_at = :now, updated_by = :by
         where id = :id
        """,
        Map.of(
            "id", ROW_ID,
            "days", days,
            "filingNo", filingNo,
            "conclusion", conclusion,
            "now", Timestamp.from(Instant.now()),
            "by", updatedBy));
    return current();
  }
}

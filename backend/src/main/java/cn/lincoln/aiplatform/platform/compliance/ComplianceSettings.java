package cn.lincoln.aiplatform.platform.compliance;

import java.time.Instant;
import java.util.UUID;

/**
 * 合规登记的平台设置（{@code #83}，单行表 {@code platform_compliance_settings}）。
 *
 * <h3>★ 这一行是四件套的「登记位」，不是流程</h3>
 *
 * <ul>
 *   <li>{@code complaintFeedbackDays}：投诉举报的反馈时限（天），<strong>运营口径</strong>， 用于公示与 {@code due_at}
 *       计算（第十五条要求「公布反馈时限」，数字由运营定）。
 *   <li>{@code algorithmFilingNo} / {@code securityAssessmentConclusion}：第十七条的算法备案号 /
 *       安全评估结论<strong>登记位</strong>（处置 = 留缝：可录入、可被「关于」引用，<strong>不建</strong>申报/变更/注销流程 ——
 *       那是流程系统不是字段）。
 *   <li>第十二条（深度合成标识）本版<strong>不落任何字段</strong>：本版不产生图片/视频等视觉生成内容， 标识没有可打的位置 —— 空壳功能就是
 *       silent-failure，处置 = 只记账（守卫断言见 {@code VisualGenerationAbsenceTest}）。
 * </ul>
 */
public record ComplianceSettings(
    int complaintFeedbackDays,
    String algorithmFilingNo,
    String securityAssessmentConclusion,
    Instant updatedAt,
    UUID updatedBy) {}

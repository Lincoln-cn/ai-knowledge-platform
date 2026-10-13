package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.AccessCredential;
import java.time.Instant;
import java.util.UUID;

/**
 * 访问凭证的对外形状（契约 {@code frontend/src/api/assistants.ts} 的 {@code AccessCredential}）。
 *
 * <p>★ 字段名与那个接口<strong>逐字对齐</strong>：契约已冻结，后端不许自己发明字段。
 *
 * <h3>★ 三个"不能省"的字段</h3>
 *
 * <ul>
 *   <li>{@code token}：列表接口要返回它（凭证管理页要把链接显示出来给人复制）。它同时也是"库里存明文" 这个取舍的<strong>原因</strong> —— 取舍写在
 *       {@link AccessCredential} 的 javadoc 里。
 *   <li>{@code maxClassification}：★ 契约把它定成<strong>必填</strong>。做成可选等于允许"没有冻结值的链接"
 *       存在，而那种链接只能靠"创建时命中过"放行 —— {@code 13 §5.2} 的「⚠️ 不得」。
 *   <li>{@code revokedAt}：撤销是终态，界面靠它显示"已撤销"。<strong>已撤销的凭证照样下发</strong> （{@code listCredentials}
 *       不筛它）：撤销是一条要被人看见的事实，藏起来等于让人以为"凭证丢了"。
 * </ul>
 *
 * <p>★ 这里<strong>不做</strong> {@code @JsonInclude(NON_NULL)}：契约的 {@code expiresAt} / {@code
 * lastAccessAt} / {@code revokedAt} 的 {@code null} 是<strong>有语义的值</strong> （null = 长期有效 / 从未访问 /
 * 未撤销），与 {@code AssistantView} 里那些"可选字段"不同 —— 省略它们会让前端分不清"字段没有"与"值是 null"。这份 record 全部下发。
 */
public record AccessCredentialView(
    UUID id,
    String token,
    boolean enabled,
    Instant expiresAt,
    String maxClassification,
    long accessCount,
    Instant lastAccessAt,
    Instant revokedAt,
    Instant createdAt) {

  public static AccessCredentialView from(AccessCredential credential) {
    return new AccessCredentialView(
        credential.id(),
        credential.token(),
        credential.enabled(),
        credential.expiresAt(),
        // ★ 密级用**机器码**（PUBLIC / INTERNAL / …）下发：与库存值、与契约的
        //   ClassificationLevel 三者同名（14 册 §2.1）。展示文案（"公开/内部/…"）只在前端 label 映射里
        credential.maxClassification() == null ? null : credential.maxClassification().name(),
        credential.accessCount(),
        credential.lastAccessAt(),
        credential.revokedAt(),
        credential.createdAt());
  }
}

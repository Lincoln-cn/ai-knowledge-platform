package cn.lincoln.aiplatform.conversation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 归档清理的留存期配置位（{@code #299} 第二半第三块「留缝」）。
 *
 * <p>★ <strong>今天这个配置位没有执行体</strong>：没有任何 {@code @Scheduled} 会按它删数据。它存在的理由是「现在不做会返工」 ——
 * 天数这个口径要与客户确认（{@code 02 §5.1.1}：「数字是口径不是承诺」）， 但<strong>配置位的名字与形状</strong>是数据模型的一部分，晚定就要改已经写死的清理路径。
 *
 * <p>★ {@code retainDays} 默认 {@code null} 而不是某个数字：issue {@code #299} 没有给留存期数字，
 * <strong>不许编</strong>。真做清理任务前必须与客户确认（{@code 02 §5.1.1} 留存期表的脚注）。 试算请用 dry-run 端点的 {@code
 * ?days=N}，那个数字来自调用方、不是产品默认值。
 *
 * <p>★ {@code enabled} 默认 {@code false}：即便 {@code retainDays} 确认了，自动清理也要客户显式打开 —— 与 {@code
 * DeletedDocumentSweeper}（{@code app.knowledge.cleanup.enabled:false}）同口径：默认不动任何数据。
 *
 * @param enabled 自动清理开关；今天恒 {@code false}（默认值由 {@link RetentionDryRunTest} 钉着）
 * @param retainDays 会话软删后多少天可彻底删除（连同消息与附件一并）；{@code null} = 待客户确认
 */
@ConfigurationProperties(prefix = "app.lifecycle.retention")
public record RetentionPolicyProperties(
    @DefaultValue("false") boolean enabled, Integer retainDays) {}

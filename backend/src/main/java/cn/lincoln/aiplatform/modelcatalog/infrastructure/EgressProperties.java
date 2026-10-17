package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 出站闸门的平台配置（{@code app.egress.*}，{@code #72}）。
 *
 * <p>★ 为什么是<strong>平台级</strong>而不是模型级：{@code #72} 原文要求的是 {@code ai_model} 上加一列 「允许处理密级上限」（与 {@code
 * kb_knowledge_base.max_classification} 同形）。那一列需要迁移， 而本批按 {@code ROADMAP §11.7.2} 的裁定走「留缝」——
 * 先用一个平台配置表达同一条口径， 保证<strong>闸门本身是真的</strong>，只是"上限"的粒度粗一级。★ 补列时的迁移号要接在 现有链尾之后（本仓 Flyway 是单一版本链，见
 * {@code ROADMAP §11.2} 那一行）。
 *
 * @param externalApiCeiling {@code EXTERNAL_API} 供应商的模型允许处理到的<strong>最高密级</strong>。 ★ 默认 {@link
 *     ClassificationLevel#INTERNAL}：即 {@code SECRET}/{@code CONFIDENTIAL} <strong>一律不得</strong>发给外部
 *     API。★ 这个默认值是<strong>刻意保守</strong>的 —— 反过来（默认最高档）会让"没配"与"允许发机密"读数一样，那正是 {@code
 *     silent-failure}。
 */
@ConfigurationProperties(prefix = "app.egress")
public record EgressProperties(ClassificationLevel externalApiCeiling) {}

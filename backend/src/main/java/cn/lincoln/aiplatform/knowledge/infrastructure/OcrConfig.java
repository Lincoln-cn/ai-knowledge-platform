package cn.lincoln.aiplatform.knowledge.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OCR 配置装配（第 9 周 · 0.3.1）。
 *
 * <p>★ 与 {@code SegmentationConfig} / {@code AiModelConfig} 同一条做法：{@code @ConfigurationProperties}
 * 的 record 自己不生效，必须显式 {@code EnableConfigurationProperties} —— 少了它，注入进来的会是一个
 * <strong>全默认值</strong>的实例（配置文件里写了、却没人读），是最难发现的一类"看起来配了"。
 *
 * <p>★ 健康指示器（{@link OcrHealthIndicator}）<strong>不做条件装配</strong>：{@code /actuator/health}
 * 要回答的是"这台机器交付后能不能用 OCR"，而这个问题在 OCR 被关掉或被漏装时<strong>更要有人回答</strong>。
 * 条件装配会把"没装"变成"这个组件不存在"，与"装了但不可用"混在一起。
 */
@Configuration
@EnableConfigurationProperties(OcrProperties.class)
public class OcrConfig {}

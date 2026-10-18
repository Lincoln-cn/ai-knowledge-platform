package cn.lincoln.aiplatform.platform.retrieval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 检索预算配置装配。
 *
 * <p>★ 与 {@code OcrConfig} / {@code SegmentationConfig} / {@code ChatStreamConfig} 同一条做法：
 * {@code @ConfigurationProperties} 的 record <strong>自己不生效</strong>，必须显式
 * {@code @EnableConfigurationProperties} —— 少了它，注入进来的是一个<strong>全默认值</strong>的实例 （`application.yml`
 * 与 `application-lowend.yml` 里写了、却没人读），是最难发现的"看起来配了"。
 *
 * <p>★ 这次是**实测撞上的**：改完代码后启动直接失败 {@code Consider defining a bean of type
 * 'RetrievalBudgetProperties'} —— 那就是"没有这个装配类"的形状。 比"注入了默认值"好查（后者会安静地跑在 5s 预算上，症状是低配又超时）。
 */
@Configuration
@EnableConfigurationProperties(RetrievalBudgetProperties.class)
public class RetrievalBudgetConfig {}

package cn.lincoln.aiplatform.conversation.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 挂载 {@link RetentionPolicyProperties}。
 *
 * <p>★ {@code @ConfigurationProperties} record 不挂 {@code @EnableConfigurationProperties} 的话，
 * 注入进来的是全默认值实例（{@code RetrievalBudgetConfig} 的同一条注记）—— 配置位会静默失灵。
 */
@Configuration
@EnableConfigurationProperties(RetentionPolicyProperties.class)
public class RetentionPolicyConfig {}

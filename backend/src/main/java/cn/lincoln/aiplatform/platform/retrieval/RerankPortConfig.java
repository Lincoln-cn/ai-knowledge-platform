package cn.lincoln.aiplatform.platform.retrieval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 重排配置装配。
 *
 * <p>★ 与 {@link RetrievalBudgetConfig} 同一条做法（也是实测撞上的教训）： {@code @ConfigurationProperties} 的 record
 * <strong>自己不生效</strong>，必须显式 {@code @EnableConfigurationProperties} ——
 * 少了它，注入进来的是一个<strong>全默认值</strong>的实例 （{@code application.yml} 里写了、却没人读），是最难发现的"看起来配了"。
 */
@Configuration
@EnableConfigurationProperties(RerankProperties.class)
public class RerankPortConfig {}

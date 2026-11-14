package cn.lincoln.aiplatform.platform.retrieval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * HNSW 扫描参数的装配。
 *
 * <p>★ 与 {@link RerankPortConfig} 是同一条教训：{@code @ConfigurationProperties} 的 record
 * <strong>自己不生效</strong>，必须显式 {@code @EnableConfigurationProperties} —— 少了它，注入进来的是一个
 * <strong>全默认值</strong>的实例，{@code application.yml} 里写了却没人读，这是最难发现的"看起来配了"。
 *
 * <p>★ 另一个方向也刻意：{@link HnswScanProperties} 的 compact 构造器会校验取值，配错了
 * <strong>启动就失败</strong>。宁可起不来，也不要带着一个"会拼进 SQL 但没人检查过的字符串"跑起来。
 */
@Configuration
@EnableConfigurationProperties(HnswScanProperties.class)
public class HnswScanConfig {}

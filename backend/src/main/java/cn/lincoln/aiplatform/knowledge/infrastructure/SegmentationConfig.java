package cn.lincoln.aiplatform.knowledge.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用分段配置绑定（{@code app.segmentation.*}）。 */
@Configuration
@EnableConfigurationProperties(SegmentationProperties.class)
public class SegmentationConfig {}

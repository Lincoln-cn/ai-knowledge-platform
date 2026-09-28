package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型目录的配置（{@code application.yml} 的 {@code app.model.*}）。
 *
 * <p>★ 用自定义前缀 {@code app.model} 而不是 {@code spring.ai.*}：后者是 Spring AI 的命名空间， 往里塞自定义键会在某次升级后撞名（与
 * {@code JwtProperties} 同一条判断）。
 *
 * @param apiKeySecret AES-256 密钥，<strong>Base64 编码的 32 字节</strong>。 ⚠️ {@code application.yml}
 *     里那个是<strong>本地开发默认值</strong>，任何非本地环境都必须覆盖 —— 这个值在仓库里等于公开： 拿到它就能解开库里所有模型的 API key。
 */
@ConfigurationProperties(prefix = "app.model")
public record ModelApiProperties(String apiKeySecret) {}

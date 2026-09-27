package cn.lincoln.aiplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * 平台入口（模块化单体）。
 *
 * <p>模块划分与依赖规则见 {@code docs/系统设计方案/01-架构设计（DDD·Spring Modulith）.md}， 由 {@code ModularityTests}
 * 在构建期强制校验（越界即失败）。
 */
@Modulithic(systemName = "ai-knowledge-platform")
@SpringBootApplication
public class AiPlatformApplication {

  public static void main(String[] args) {
    SpringApplication.run(AiPlatformApplication.class, args);
  }
}

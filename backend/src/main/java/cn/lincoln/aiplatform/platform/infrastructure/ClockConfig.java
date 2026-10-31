package cn.lincoln.aiplatform.platform.infrastructure;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用里唯一的时钟（`java.time.Clock`）。
 *
 * <p>为什么值得单独有它：凡是"按天数算"的逻辑（留存期清理、过期时间、超时判定）都需要一个可替换的 现在 ——
 * 否则判据只能写"大约在现在前后"，那种断言在时钟跨越午夜时会自己红一次，然后被人删掉。
 *
 * <p>★ 一律用 UTC：本项目里所有时间列都是 `timestamptz`，展示层的时区由前端决定。 在服务端用本地时区算截止时间，会在夏令时或容器时区不同时算错一天。
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}

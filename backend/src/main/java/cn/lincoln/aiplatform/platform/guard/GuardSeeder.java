package cn.lincoln.aiplatform.platform.guard;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时把围栏配置读一次，并在规则表为空时用环境变量播种（{@code ROADMAP §11.53.5}）。
 *
 * <h3>★ 为什么在启动时就要读一次，而不是等第一个请求</h3>
 *
 * <p>{@link GuardStore} 的失败方向是 fail-closed：从未成功加载过就抛。把这次"第一次加载"放在启动期，
 * 好处是"库连不上/迁移没跑"这类问题在<b>部署时</b>暴露（服务起不来），而不是在<b>第一个用户提问时</b> 暴露（那个用户会看到一个 500，而运维要到有人投诉才知道）。
 *
 * <p>★ 这与 {@code RetrievalEngineSettingsStore} 刻意不同：那边读不到就回落 yml，因为检索必须能用； 这边读不到宁可起不来 ——
 * 一个"以为有围栏、实际没有"的部署比一个起不来的部署危险得多。
 *
 * <h3>★ {@code CHAT_GUARD_SENSITIVE_WORDS} 的角色变了</h3>
 *
 * <p>以前它是<b>唯一</b>真源。现在它只在规则表为空时播种一次，之后库为准；表里已有规则时它不再生效 （带 WARN）。理由见 {@link
 * GuardStore#seedFromEnvironment}：两条真源同时存在时，"为什么删了还在拦" 永远没有答案。
 */
@Component
public class GuardSeeder {

  private static final Logger log = LoggerFactory.getLogger(GuardSeeder.class);

  private final GuardStore store;

  private final String rawSensitiveWords;

  public GuardSeeder(
      GuardStore store, @Value("${app.chat.guard.sensitive-words:}") String rawSensitiveWords) {
    this.store = store;
    this.rawSensitiveWords = rawSensitiveWords;
  }

  /** 首次加载 + 播种。★ 加载失败会让启动失败（fail-closed）。 */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    GuardSnapshot snapshot = store.reload();
    log.info(
        "安全围栏已加载：输入检查={} advisor 链={} 分类话术={} · {} 个分类 / {} 条规则",
        snapshot.settings().inputEnabled(),
        snapshot.settings().advisorsEnabled(),
        snapshot.settings().categoryRefusalEnabled(),
        snapshot.categories().size(),
        snapshot.rules().size());
    int seeded = store.seedFromEnvironment(words(rawSensitiveWords));
    if (seeded > 0) {
      log.info("已从 CHAT_GUARD_SENSITIVE_WORDS 播种 {} 条规则（后续请在该页维护）", seeded);
    }
  }

  private static List<String> words(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (String word : raw.split(",")) {
      if (!word.isBlank()) {
        result.add(word.trim());
      }
    }
    return List.copyOf(result);
  }
}

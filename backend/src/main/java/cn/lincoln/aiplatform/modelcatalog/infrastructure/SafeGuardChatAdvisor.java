package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 官方 {@link SafeGuardAdvisor} 的扩展（{@code 13 §12.1} 拍板的口径：扩展，不自研）。
 *
 * <p>★ 为什么是"扩展"而不是另写一套拦截：官方 advisor 已经站在调用链上，另写一套会出现两道语义不同的网 ——
 * 一道拦输入、一道拦输出，最后没人说得清哪一道在生效。这里只提供<strong>词表与拒绝话术</strong>，判定交给官方。
 *
 * <p>词表来自 {@code app.chat.guard.sensitive-words}（逗号分隔，空 = 不拦）。★ 默认空不是"忘了配"：
 * 把词表写死在代码里会让"哪些话不能问"变成改代码才能变的事，而它是运营口径。
 */
@Component
public class SafeGuardChatAdvisor extends SafeGuardAdvisor {

  /** 拒绝话术：不复述命中词，避免把"你说了敏感词"变成可探测的 oracle。 */
  public static final String REFUSAL = "抱歉，这个问题不在可回答范围内。";

  public SafeGuardChatAdvisor(
      @Value("${app.chat.guard.sensitive-words:}") String rawSensitiveWords) {
    super(words(rawSensitiveWords), REFUSAL, 100);
  }

  @Override
  public String getName() {
    return "safeguard-chat";
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

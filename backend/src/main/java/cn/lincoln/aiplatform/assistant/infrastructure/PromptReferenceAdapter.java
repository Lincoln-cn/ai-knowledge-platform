package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.PromptConfig;
import cn.lincoln.aiplatform.assistant.domain.PromptReferencePort;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 提示词引用的仓储实现 —— 只回答"这些 id 里哪些还在"。 */
@Component
public class PromptReferenceAdapter implements PromptReferencePort {

  private final JpaPromptConfigRepository prompts;

  public PromptReferenceAdapter(JpaPromptConfigRepository prompts) {
    this.prompts = prompts;
  }

  @Override
  public Set<UUID> existing(Collection<UUID> promptIds) {
    if (promptIds == null || promptIds.isEmpty()) {
      return Set.of();
    }
    return prompts.findAllById(promptIds).stream()
        .map(PromptConfig::id)
        .collect(Collectors.toUnmodifiableSet());
  }
}

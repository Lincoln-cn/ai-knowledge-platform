package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.Assistant;
import cn.lincoln.aiplatform.assistant.domain.AssistantRepository;
import cn.lincoln.aiplatform.platform.access.AccessScopePort;
import cn.lincoln.aiplatform.platform.assistant.AppOpeningPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link AppOpeningPort} 的实现 —— 端口在 {@code platform}、实现在提供方模块（{@code 08 §4.4.2}）。
 *
 * <p>★ 与 {@link PublicAppAdapter} 逐字同构的 {@code findById + canSee} 两行，为什么还要单独一个类：
 * 因为「可见性判定只有一处」（{@code 07 §3.3}）这条纪律的价值在于<strong>每个消费点都走同一处</strong>。 若让会话创建那一侧自己 {@code findById}
 * 再比 {@code ownerId / visibility}，那就是第二份判定， 而它与 {@link AccessScopePort}
 * 的漂移是静默的（看得见应用、却复制不到它的开场白，或反过来）。
 *
 * <p>★ 三步的顺序：① 先按 id 取（{@code AssistantRepository.findById} 已滤掉软删）； ② 再问 {@code canSee}（★
 * 那一处内部认的是当前生效范围）；③ 最后归一化开场白。 前两步任一失败都只给出一个 {@link Optional#empty()} —— 与 {@link PublicAppAdapter}
 * 同一条口径： "不存在"与"看不见"必须不可分。
 */
@Component
public class AppOpeningAdapter implements AppOpeningPort {

  private final AssistantRepository assistants;
  private final AccessScopePort accessScope;

  public AppOpeningAdapter(AssistantRepository assistants, AccessScopePort accessScope) {
    this.assistants = assistants;
    this.accessScope = accessScope;
  }

  @Override
  public Optional<String> prologueOf(UUID appId) {
    if (appId == null) {
      // ★ 通用问答（没有应用）没有开场白 —— 空，而不是"也许有这个应用"
      return Optional.empty();
    }
    return assistants
        .findById(appId)
        .filter(accessScope::canSee)
        .map(Assistant::prologue)
        // ★ 空白开场白 ⇔ 没有开场白：调用方据此"不造消息"。这里归一化而不是让调用方判断，
        //   是因为"没填开场白"在库里既可能是 '' 也可能是若干空格（V29 的默认值是 ''，但表单可能存进空格）。
        .map(String::strip)
        .filter(text -> !text.isEmpty());
  }
}

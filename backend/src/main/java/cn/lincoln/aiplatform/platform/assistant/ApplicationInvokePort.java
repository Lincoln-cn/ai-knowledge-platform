package cn.lincoln.aiplatform.platform.assistant;

import java.util.Optional;
import java.util.UUID;

/**
 * 调用另一个编排应用的<strong>已发布版本</strong>（能力端口；实现在 {@code assistant} 模块）。
 *
 * <p>★ 只认已发布版本，不认草稿：草稿是"边画边存"的半成品（{@code PUT /workflow/draft} 明确允许不合规的图），
 * 把一张没通过发布校验的图接进别人的流程，等于让"谁是这条链的出口"变成一件随时会变的事。 没发布过 ⇒ {@link
 * Optional#empty()}，由调用方决定文案（"这个应用还没有发布版本"）。
 *
 * <p>★ 端口放在 {@code platform} 而不是 {@code assistant} 直连，是为了让"能调用别人"这件事 在依赖方向上只有一个入口：{@code assistant}
 * 的 {@code @ApplicationModule} 允许自己依赖自己， 但这样一来"应用调用应用"的递归就没人拦得住 —— 端口实现里带层级上限，拦在设计上而不是靠自觉。
 *
 * <p>★ 返回值里<strong>没有</strong>子执行 id：父流程是一次执行，把子执行 id 塞进节点配置/状态会让
 * "这次运行涉及几次执行"变成两个真源。要追子流程，看它的运行记录（它自己会落一条）。
 */
public interface ApplicationInvokePort {

  /**
   * 用 {@code input} 跑一次被调应用，拿到它的最终输出。
   *
   * @param appId 被调应用
   * @param versionKey 版本号或版本主键；空白 = 最新已发布版本
   * @param input 入口输入（为 {@code null} 时按空串跑：开场白类应用不需要输入也能给出结果）
   * @return 空 = 应用不存在或还没有发布版本
   * @throws IllegalStateException 递归层级超限 / 被调应用的已发布图编译不过
   */
  Optional<Invocation> invoke(UUID appId, String versionKey, String input);

  /** 子流程的最终输出。空串是合法结果（子流程的回复节点写了空文案）。 */
  record Invocation(String output) {}
}

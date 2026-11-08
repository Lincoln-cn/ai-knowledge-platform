package cn.lincoln.aiplatform.platform.assistant;

import java.util.Optional;
import java.util.UUID;

/**
 * 应用开场白的<strong>只读窄端口</strong>（{@code #286}）。
 *
 * <h3>★ 为什么是第三个端口，而不是复用已有的两个</h3>
 *
 * <ul>
 *   <li>{@link AppBindingPort} 带的是「问答那一轮真正要用的最小事实」（模型 / 知识库 / 检索参数）。 开场白不在其中 ——
 *       把它塞进去，会让<strong>每一次问答</strong>都多读一段那一轮用不到的正文； 而"最小事实"这条边界正是那个端口存在的理由。
 *   <li>{@link PublicAppPort} 带的是「访客还没提问之前看到的那一屏」（名称 / 图标 / 描述 / 开场白 / 推荐问题）。
 *       会话创建发生在<strong>登录用户</strong>这一侧，两者可见性主体与用途都不同： 公开面是"匿名访客能不能看这个应用"，会话面是"当前登录用户能不能用这个应用"。
 * </ul>
 *
 * <p>★ 空 {@link Optional} 的语义是<strong>合并</strong>的：应用不存在、当前主体看不见、开场白为空 ——
 * 三种情况对调用方是同一件事（不落开场白消息，也不报错）。所以端口不再细分， 调用方也不需要写三个分支。
 */
public interface AppOpeningPort {

  /**
   * 取应用的开场白。
   *
   * @param appId 应用 id（{@code null} ⇒ 空）
   * @return 去首尾空白后的开场白；应用不存在 / 看不见 / 开场白为空 ⇒ {@link Optional#empty()}
   */
  Optional<String> prologueOf(UUID appId);
}

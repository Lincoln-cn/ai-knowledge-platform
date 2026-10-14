package cn.lincoln.aiplatform.platform.assistant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 公开面（免登录）要用的<strong>应用展示面</strong>端口 —— 接口放 {@code platform}、实现放 {@code assistant}。
 *
 * <h3>★★ 为什么不复用 {@link AppBindingPort}</h3>
 *
 * <p>{@link AppBindingPort} 是<strong>问答那一轮</strong>要的三个事实（{@code appId} / {@code modelId} / {@code
 * kbIds}），它的类注释把边界写死了：「应用的名称、提示词 / 检索参数、发布状态、调用计数都不在这里」。 公开面要的是另一组事实 ——
 * 访客在<strong>还没提问之前</strong>看到的那一屏： 应用叫什么、什么图标、什么描述、开场白、推荐问题。
 *
 * <p>★ 把两者合成一个端口，就得让"问答那一轮"也认识名称与图标（它一个都不用）， 而端口一旦变宽，下一个消费者就会顺手往里塞第三个用途 —— 那正是本项目反复记账的那种慢性腐蚀。 窄端口 +
 * 每个用途一个，是这个包（{@code platform.assistant}）既有的形状。
 *
 * <h3>★★ 为什么 {@code assistant} 之外只有 {@code platform} 能拿到它</h3>
 *
 * <p>公开面的两个 HTTP 端点落在 {@code conversation} 侧（那里是问答引擎的家）， 而 {@code conversation} 的
 * {@code @ApplicationModule} 白名单里<strong>没有</strong> {@code assistant}（{@code 第 17 周} 刚撤下， 见 {@code
 * conversation/package-info.java}）。所以它读应用展示面只能走这一条跨模块 SPI —— 与 {@code AccessScopePort} / {@code
 * SubjectIdentityPort} / {@code AppBindingPort} 同一条口径。
 *
 * <h3>★★ 可见性判定仍然只有一处</h3>
 *
 * <p>实现里问的是 {@link cn.lincoln.aiplatform.platform.access.AccessScopePort#canSee} ——
 * <strong>不</strong>自己比 {@code ownerId}、也<strong>不</strong>看 {@code visibility}。 原因与 {@code
 * AppBindingPort} 完全相同：「归属 + 可见性 + 共享」三条的合成只有那一处实现，抄一遍就多一处会跟着改的地方。
 *
 * <h3>★ 它是公开面上"第一屏"的唯一取数口，但它<strong>不</strong>回答"这个 token 有效吗"</h3>
 *
 * <p>令牌的可用性（存在 / 已撤销 / 已过期 / 已停用）是 {@code PublicTokenResolver} 的事 （{@code 08 §4.2.3}）。
 * 本端口只回答"这个应用现在长什么样、以当前生效范围看不看得见"。
 */
public interface PublicAppPort {

  /**
   * 一次公开访问要展示的应用事实（字段与契约 {@code PublicContext} 的前六个<strong>逐一对应</strong>）。
   *
   * <p>★ 刻意<strong>不含</strong> {@code maxClassification}：那是凭证上<strong>创建时冻结</strong>的值 （{@code 13
   * §5.2} 第 ① 级），由 {@code PublicTokenResolver} 解析出来，而不是应用自身的属性。 把它塞进"应用展示面"会让"冻结值从哪来"多出第二个真源。
   *
   * @param appId 应用 id（契约 {@code PublicContext.appId}）
   * @param name 应用名（契约 {@code appName}）
   * @param icon 图标（emoji 串，不是 URL —— 契约里就是这么定的）
   * @param description 描述；★ 库里可为 {@code null}（{@code V29} 的列没有 not null）⇒ 这里归一成空串， 因为契约的 {@code
   *     description} 是必填字符串，"没有描述"与"字段缺失"在公开页上是同一屏
   * @param prologue 开场白（可为空串）
   * @param quickQuestions 推荐问题；★ 空列表 = 没有推荐问题，不是"字段缺失"
   */
  record PublicApp(
      UUID appId,
      String name,
      String icon,
      String description,
      String prologue,
      List<String> quickQuestions) {

    /** 集合一律防御性拷贝：它会被序列化进响应，也可能会在多处被读。 */
    public PublicApp {
      quickQuestions = quickQuestions == null ? List.of() : List.copyOf(quickQuestions);
    }
  }

  /**
   * 取公开面要展示的那个应用。
   *
   * @param appId 凭证指向的应用 id
   * @return 应用展示面；★ <strong>空 = 不存在或以当前生效范围看不见</strong>（两者同一个出口， 调用方一律按"这条链接的目标不在了"处置，不去分辨是哪一种）
   */
  Optional<PublicApp> findVisibleById(UUID appId);
}

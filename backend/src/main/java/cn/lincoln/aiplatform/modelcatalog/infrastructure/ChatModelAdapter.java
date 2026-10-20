package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.modelcatalog.domain.ResolvedUpstream;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatMessage;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * 对话模型端口的实现（第 12 周 · 0.4.0）—— 走 OpenAI 兼容的上游 {@code /chat/completions}。
 *
 * <p>与 {@code UpstreamEmbeddingAdapter} 同一条边界：<strong>解密 API key 是这里的职责</strong>，
 * 业务模块拿不到也不该拿到明文。差别只有一处 —— 向量模型是"知识库上绑好的"， 而对话模型现在<strong>没有绑定方</strong>（应用配置是第 15
 * 周），所以这里要负责"挑一个可用的"。
 *
 * <h3>挑模型的规则（以及为什么是它）</h3>
 *
 * <p>{@link #pickChatModel()} 取<strong>第一个已加载的对话模型</strong>（按仓储返回顺序）。
 * 这条规则的价值不在于"选得好"，而在于<strong>确定</strong>：同一个库上重复跑，挑到的是同一个 —— 否则"昨天能查、今天降级了"会变成一件查不出原因的事。等第 15
 * 周应用能绑模型时， 这个方法的调用点会被"应用绑定的模型"替换，而端口形状不用改。
 *
 * <p>★ 排除 {@code LOAD_FAILED} 与未加载的：把没加载的模型挑出来，只会在上游拿到一个 404， 而那时的错误信息是"模型不存在"——
 * 与真正的原因（管理员没点加载）差得很远。
 *
 * <h3>★ 两个出口：原文 与 结构化</h3>
 *
 * <p>{@link #complete} 返回原文（调用方按简单分隔自己读，如查询理解的多条查询）；{@link #completeAs} 用官方 {@link
 * BeanOutputConverter} 生成格式说明并解析（如多跳判定）。<strong>容错解析不写在这里</strong> ——
 * 官方转换器已经在做围栏/前后废话的处理，自研一份只会多一处要维护的猜测；这里只把"转换失败"统一成一次可读的调用失败。
 */
@Component
public class ChatModelAdapter implements ChatPort {

  private final ModelRepository models;
  private final ModelUpstreamResolver upstream;
  private final UpstreamGateway gateway;

  public ChatModelAdapter(
      ModelRepository models, ModelUpstreamResolver upstream, UpstreamGateway gateway) {
    this.models = models;
    this.upstream = upstream;
    this.gateway = gateway;
  }

  @Override
  public Optional<UUID> pickChatModel() {
    List<Model> candidates =
        models.search(null, ModelKind.CHAT, ModelStatus.LOADED, 0, MAX_CANDIDATES_SCAN);
    // ★ 服务商停用 ⇒ 这个模型不可用（与 resolve 的 fail-closed 同一口径），不能挑到它。
    return candidates.stream()
        .filter(model -> upstream.egressOf(model).isPresent())
        .map(Model::id)
        .findFirst();
  }

  @Override
  public String complete(UUID modelId, String prompt, Duration timeout) {
    return chat(requireChatModel(modelId), prompt, timeout);
  }

  /**
   * ★ **覆盖**端口的默认实现（`#168`）：走 `messages[]`，每条各带 role。
   *
   * <p>端口那个默认实现是把消息拼成一段文本 —— 那会丢掉 role 边界，而丢了边界的多轮 正是反向注入（{@code #84}）的形状。★ 生产路径必须走本方法。
   */
  @Override
  public String completeConversation(UUID modelId, List<ChatMessage> messages, Duration timeout) {
    Model model = requireChatModel(modelId);
    List<Map<String, String>> history =
        messages.stream().map(m -> Map.of("role", m.wireRole(), "content", m.content())).toList();
    try {
      ResolvedUpstream target = upstream.resolve(model);
      return gateway.chatCompletion(
          model.baseModel(),
          target.apiBase(),
          target.apiKey(),
          history,
          timeout,
          target.authStyle());
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "调用对话模型失败：" + e.getMessage(),
          "请确认对话模型服务可用（地址与密钥）；检索会退回原始问题继续，不会因此失败");
    }
  }

  /**
   * ★★ 真流式：逐 token 交给调用方，而不是等整段返回再切块。
   *
   * <h3>为什么需要它（实测数据）</h3>
   *
   * <p>{@link #complete} / {@link #completeConversation} 都是「一次性返回」： CPU 上 qwen2.5:1.5b 生成 400 字要
   * <b>60~90 秒</b>，这期间服务端一个字节都发不出去 ⇒ 用户看到「转 90 秒，然后一次性出现全文」。而 Ollama 本身逐 token 吐，
   * 是中间这一层（`ChatPortFallbackStreamAdapter` 拿 {@code complete()} 再按 24 字切块） 把它攒住了。
   *
   * <p>★ 本方法只做"转发"，不决定终态、不落库 —— 那两件事仍由 {@code ChatService} 的收尾路径做。
   *
   * @param onDelta 每收到一段正文回调一次（★ 调用方抛异常即可中断，上游连接随之关闭）
   * @return 上游是否真的按流式返回了数据
   */
  public boolean streamChat(
      UUID modelId,
      List<cn.lincoln.aiplatform.platform.model.ChatMessage> messages,
      Duration timeout,
      java.util.function.Consumer<String> onDelta) {
    return streamChat(modelId, messages, false, timeout, onDelta);
  }

  /**
   * 带应用思考开关的流式调用（{@code #185}）。
   *
   * <p>★★ 这里只做<strong>能力映射</strong>，不替用户决定：{@code TOGGLEABLE} 才发送 {@code reasoning_effort}（开 =
   * {@code high}，关 = {@code none}）；{@code ALWAYS_ON} 与 {@code NONE} 都不发送 ——
   * 对前者"关"物理上做不到，对后者"开"没有意义。把 {@code none} 发给一个始终思考的模型， 只会得到一个被忽略的字段，却让日志看起来"已经关了"。
   */
  public boolean streamChat(
      UUID modelId,
      List<cn.lincoln.aiplatform.platform.model.ChatMessage> messages,
      boolean thinking,
      Duration timeout,
      java.util.function.Consumer<String> onDelta) {
    Model model = requireChatModel(modelId);
    List<Map<String, String>> history =
        messages.stream().map(m -> Map.of("role", m.wireRole(), "content", m.content())).toList();
    String reasoningEffort =
        model.thinking() == cn.lincoln.aiplatform.modelcatalog.domain.ThinkingMode.TOGGLEABLE
            ? (thinking ? "high" : "none")
            : null;
    try {
      ResolvedUpstream target = upstream.resolve(model);
      return gateway.streamChatCompletion(
          model.baseModel(),
          target.apiBase(),
          target.apiKey(),
          history,
          timeout,
          onDelta,
          target.authStyle(),
          reasoningEffort);
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "调用对话模型失败：" + e.getMessage(),
          "请确认对话模型服务可用（地址与密钥）；检索会退回原始问题继续，不会因此失败");
    }
  }

  @Override
  public <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout) {
    Model model = requireChatModel(modelId);
    // ★ 格式说明（"只输出 JSON" + schema）由官方转换器生成，我们不手写这一段
    BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
    String raw = chat(model, prompt + FORMAT_SEPARATOR + converter.getFormat(), timeout);

    T value;
    try {
      value = converter.convert(raw);
    } catch (RuntimeException e) {
      // 官方转换器抛的是运行时异常（转换失败）；统一成"这一次调用失败"，让调用方按同一路径降级
      throw parseFailure(type, e.getMessage());
    }
    if (value == null) {
      throw parseFailure(type, "转换器产出了空对象");
    }
    return value;
  }

  /** 取模型并校验类型 —— 两个出口共用（`complete` 与 `completeAs` 对"模型合不合法"的判断必须是同一套）。 */
  private Model requireChatModel(UUID modelId) {
    Model model =
        models
            .findById(modelId)
            .orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "对话模型不存在", "它可能已被删除，请重新选择一个对话模型"));
    if (model.kind() != ModelKind.CHAT) {
      // 类型不对要明确拒绝：把向量模型当对话模型用，拿回来的不是正文
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "「" + model.name() + "」不是对话模型（kind=" + model.kind() + "）",
          "本调用需要 CHAT 类型且已加载的模型");
    }
    return model;
  }

  private String chat(Model model, String prompt, Duration timeout) {
    try {
      ResolvedUpstream target = upstream.resolve(model);
      return gateway.chatCompletion(
          model.baseModel(),
          target.apiBase(),
          target.apiKey(),
          prompt,
          timeout,
          target.authStyle());
    } catch (UpstreamGateway.UpstreamUnreachableException e) {
      throw new BusinessException(
          ErrorCode.UPSTREAM_UNAVAILABLE,
          "调用对话模型失败：" + e.getMessage(),
          "请确认对话模型服务可用（地址与密钥）；检索会退回原始问题继续，不会因此失败");
    }
  }

  private static BusinessException parseFailure(Class<?> type, String detail) {
    return new BusinessException(
        ErrorCode.UPSTREAM_UNAVAILABLE,
        "模型输出不符合 " + type.getSimpleName() + " 的结构：" + detail,
        "上游模型没有按 schema 返回；本次调用按失败处理，调用方会用已有结果继续");
  }

  /** 提示词与格式说明之间空一行（格式说明是多行文本，贴在一起容易被模型当成同一段指令）。 */
  private static final String FORMAT_SEPARATOR = "\n\n";

  /** 扫多少个已加载的对话模型就够 —— 现在只取第一个，给一个上限免得把整张表拉进内存。 */
  private static final int MAX_CANDIDATES_SCAN = 10;
}

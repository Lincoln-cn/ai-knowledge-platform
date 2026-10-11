package cn.lincoln.aiplatform.modelcatalog.infrastructure;

import cn.lincoln.aiplatform.modelcatalog.domain.Model;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelKind;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelRepository;
import cn.lincoln.aiplatform.modelcatalog.domain.ModelStatus;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.List;
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
  private final ApiKeyCipher apiKeyCipher;
  private final UpstreamGateway gateway;

  public ChatModelAdapter(
      ModelRepository models, ApiKeyCipher apiKeyCipher, UpstreamGateway gateway) {
    this.models = models;
    this.apiKeyCipher = apiKeyCipher;
    this.gateway = gateway;
  }

  @Override
  public Optional<UUID> pickChatModel() {
    List<Model> candidates =
        models.search(null, ModelKind.CHAT, ModelStatus.LOADED, 0, MAX_CANDIDATES_SCAN);
    return candidates.stream().map(Model::id).findFirst();
  }

  @Override
  public String complete(UUID modelId, String prompt, Duration timeout) {
    return chat(requireChatModel(modelId), prompt, timeout);
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
      return gateway.chatCompletion(
          model.baseModel(), model.apiBase(), decryptKey(model), prompt, timeout);
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

  /** 解密模型的 API key（没存 key 时返回 null，由 {@link UpstreamGateway} 用默认 key）。 */
  private String decryptKey(Model model) {
    if (model.apiKeyCipher() == null || model.apiKeyIv() == null) {
      return null;
    }
    return apiKeyCipher.decrypt(model.apiKeyCipher(), model.apiKeyIv());
  }
}

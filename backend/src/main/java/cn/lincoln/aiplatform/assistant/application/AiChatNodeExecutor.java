package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code AI_CHAT_NODE}：把「检索结果 + 用户问题」交给模型，产出一段回答。
 *
 * <p>★ <strong>这是 0.7.1 补的那个洞。</strong> {@code LinearWorkflowExecutor.SUPPORTED_TYPES} 从 0.7.0 起就写着
 * 四个类型（{@code START_NODE} / {@code SEARCH_DATASET_NODE} / {@code AI_CHAT_NODE} / {@code
 * REPLY_NODE}）， 而 {@code NodeExecutor} 的实现只有三个 —— 于是带 AI 对话节点的图**能通过发布校验、点试用必炸** （{@code
 * registry.get("AI_CHAT_NODE")} 拿不到执行器）。发布校验读的就是这个集合，所以集合里写了谁就必须真有谁。
 *
 * <p>★ <strong>为什么直接用 {@link ChatPort} 而不是走对话链路（{@code ChatService}）</strong>：{@code ChatPort} 在
 * {@code platform.model} 下，{@code assistant} 本来就能依赖它；而 {@code conversation} 是另一条模块边界， {@code
 * assistant → conversation} 的依赖会让 {@code ModuleBoundaryTest} 红（{@code #293} 栽过一次：为了省一个端口，
 * 后来不得不把实现类开成 {@code @NamedInterface}）。编排节点要的是「一次补全」，不是「一个会话」—— 会话、附件、引用卡片这些都不属于一张流程图的节点。
 *
 * <p>★ <strong>检索结果缺席时不报错，而是当成「没有资料的回答」</strong>：{@code SEARCH_DATASET_NODE} 可能因为
 * 知识库为空、阈值太高、或者压根没连前端就返回空。这种情况下用户要的是「模型照问题回答并说明没找到资料」， 而不是一个把整张图打断的异常。真正的失败（模型未配置、调用超时）才抛出去。
 */
@Component
public class AiChatNodeExecutor implements NodeExecutor {

  private static final Logger log = LoggerFactory.getLogger(AiChatNodeExecutor.class);

  /** 本节点写进流程状态的键：模型产出的回答。 */
  public static final String ANSWER_KEY = WorkflowNodeKeys.Output.ANSWER;

  /** 节点的 {@code properties.config} 里读模型 id 的键。 */
  static final String CONFIG_MODEL_ID = WorkflowNodeKeys.Config.MODEL_ID;

  /** 一次补全的上限。与对话链路的口径一致，慢于此值多半是上游不可达。 */
  static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final ChatPort chatPort;

  public AiChatNodeExecutor(ChatPort chatPort) {
    this.chatPort = chatPort;
  }

  @Override
  public String type() {
    return "AI_CHAT_NODE";
  }

  @Override
  public void execute(WorkflowNodeContext context) {
    String question = context.input() == null ? "" : context.input().trim();
    if (question.isEmpty()) {
      throw new IllegalStateException("AI 对话节点没有拿到问题：START_NODE 之前没跑，或者这次执行没有输入");
    }

    String retrieval =
        Optional.ofNullable(context.state().read(SearchDatasetNodeExecutor.RESULT_KEY))
            .map(Object::toString)
            .orElse("");

    UUID modelId = modelIdOf(context.nodeConfig());
    String prompt = buildPrompt(question, retrieval);

    log.debug("#workflow AI 对话节点开始：model={} 检索资料长度={}", modelId, retrieval.length());
    String answer = chatPort.complete(modelId, prompt, TIMEOUT);
    if (answer == null || answer.isBlank()) {
      // 空回答是"上游返回了 200 但没有正文"，与"调用失败"不是一回事，但在流程里后果一样（用户看不到任何东西）。
      throw new IllegalStateException("AI 对话节点拿到了空回答：模型 " + modelId + " 返回了空正文");
    }

    context.state().write(ANSWER_KEY, answer, KeyUpdate.REPLACE);
  }

  /**
   * 从节点配置里取模型 id。
   *
   * <p>★ 配了就用配的，没配就取平台默认（{@link ChatPort#pickChatModel()}）。这条取舍的理由： 编排节点的 {@code modelId}
   * 在画布上是**可选**的（{@code base-node} 那边展示的是"AI 模型·某模型" 这种提示文案），把一个可选字段做成必填会让所有老图在升级后突然不能跑。
   */
  private UUID modelIdOf(Map<String, Object> config) {
    Object raw = config == null ? null : config.get(CONFIG_MODEL_ID);
    if (raw instanceof String text && !text.isBlank()) {
      try {
        return UUID.fromString(text);
      } catch (IllegalArgumentException ignored) {
        // 落回默认模型：画布上存过名字（不是 id）的历史数据不该让整张图跑不动。
        log.warn("#workflow AI 对话节点的 modelId 不是合法 UUID（{}），改用平台默认模型", text);
      }
    }
    return chatPort
        .pickChatModel()
        .orElseThrow(() -> new IllegalStateException("AI 对话节点没有可用模型：既没在节点上配，平台也没有默认对话模型"));
  }

  /**
   * 拼提示词。
   *
   * <p>资料为空时**不**写「以下是没有资料的资料：」这种空段，直接把问题交给模型 —— 否则模型会对着一个空的"参考资料"标题编内容，那正是幻觉最常见的来源。
   */
  static String buildPrompt(String question, String retrieval) {
    if (retrieval == null || retrieval.isBlank()) {
      return "请回答下面的问题。没有提供参考资料，如果无法确定，请直接说明。\n\n问题：" + question;
    }
    return "请依据下面的参考资料回答问题。资料里没有的内容不要编造；如果资料不足以回答，请直接说明。\n\n"
        + "参考资料：\n"
        + retrieval
        + "\n\n问题："
        + question;
  }
}

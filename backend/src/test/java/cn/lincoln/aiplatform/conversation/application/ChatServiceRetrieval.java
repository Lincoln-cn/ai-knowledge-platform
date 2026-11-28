package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.platform.model.ChatPort;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.retrieval.QaRecallPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalBudgetProperties;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import cn.lincoln.aiplatform.retrieval.application.MultiHopService;
import cn.lincoln.aiplatform.retrieval.application.RetrievalService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 把 {@link ChatService} 接上一个用「它自己那套假端口」装出来的 {@link RetrievalService}。
 *
 * <p>★★ 为什么需要它：`#378` 收口之后，对话链路不再自己写检索策略，而是调 {@code RetrievalService.retrieve}
 * （与命中测试、工作流检索节点同一段代码）。而此前那一批测试是"造一个假 {@code SegmentSearchPort}，断言检索结果如何变成引用" —— 它们要断言的活还在 {@code
 * ChatService} 里（阈值按域分档、出站闸门、引用组装），但"候选从哪来"已经搬到检索服务。
 *
 * <p>★ 所以这里做的是"把参数原样转过去"：从被测对象上取出它已经持有的 {@code segmentSearch} / {@code embeddingPort}， 拿它们装一个真
 * {@code RetrievalService}。★ 刻意<b>不</b>让测试各自再写一遍装配 —— 那样每加一个检索依赖（实体、关系、问答对、 重排）就要改 9
 * 个文件，而它们本来都不关心这些。
 *
 * <p>★ 反射读私有字段是有意为之：这三个字段由 {@code ChatService} 的构造器持有，测试替身正是通过同一个构造器交进去的，
 * 读回来的就是"它真的会用的那一个"。字段改名会立刻在这里炸，不会悄悄换成另一个实例。
 */
public final class ChatServiceRetrieval {

  private ChatServiceRetrieval() {}

  /**
   * 给 {@code service} 接上一个检索服务（就地修改，返回同一个实例便于链式调用）。
   *
   * @param service 被测的对话服务
   * @return 同一个实例
   */
  public static ChatService wire(ChatService service) {
    service.setRetrievalService(build(service));
    return service;
  }

  /**
   * 接上检索服务，并给检索服务挂一个问答对端口（第五路）。
   *
   * <p>★ 为什么端口挂在<b>检索服务</b>上而不是对话服务上：`#378` 收口之后，问答对扩召回是检索策略的一部分 （和实体/关系扩召回同一处），对话侧不再持有它 ——
   * 否则又会变成"两处都能配一条路"。
   *
   * @param service 被测的对话服务
   * @param qa 问答对端口
   * @return 同一个实例
   */
  public static ChatService wire(ChatService service, QaRecallPort qa) {
    RetrievalService built = build(service);
    attachQa(built, qa);
    service.setRetrievalService(built);
    return service;
  }

  /** 用 {@code service} 已持有的端口装一个检索服务（多跳关闭、重排 noop、查询理解按配置关闭）。 */
  public static RetrievalService build(ChatService service) {
    return new RetrievalService(
        field(service, "segmentSearch", SegmentSearchPort.class),
        lazyEmbedding(service),
        null,
        new MultiHopService(new SilentChat()),
        // ★ 关掉查询理解：这些测试断言的是"检索结果 → 引用"的转换，不该被一次改写调用打乱；
        //   同时它也免掉一个非空依赖（queryUnderstanding 传 null 只有在配置关闭时才安全）。
        new RetrievalBudgetProperties(5000, 5000, 0.6, false, false),
        RerankPort.noop(),
        RerankProperties.defaults());
  }

  /**
   * ★ 向量端口是**延迟读**的，不是装的时候读一次。
   *
   * <p>原因很具体：不少测试的顺序是「构造 {@code ChatService} → wire → {@code setEmbeddingPort(...)}」，
   * 装检索服务的那一刻端口还没到。而"端口为 null"在向量模式下应当是**一条可读的失败**（与 {@code ChatService.vectorHits} 原先那句「检索模式为
   * VECTOR，但 EmbeddingPort 未装配」同义）， 不能变成"检索悄悄只跑全文"。
   */
  private static EmbeddingPort lazyEmbedding(ChatService service) {
    return (modelId, texts) -> {
      EmbeddingPort real = field(service, "embeddingPort", EmbeddingPort.class);
      if (real == null) {
        throw new IllegalStateException("检索要走向量路，但 EmbeddingPort 未装配");
      }
      return real.embed(modelId, texts);
    };
  }

  private static void attachQa(RetrievalService service, QaRecallPort qa) {
    try {
      Method setter = service.getClass().getDeclaredMethod("setQaRecallPort", QaRecallPort.class);
      setter.setAccessible(true);
      setter.invoke(service, qa);
    } catch (ReflectiveOperationException denied) {
      throw new IllegalStateException("接不上问答对端口 —— 检索服务那边改名了？", denied);
    }
  }

  private static <T> T field(Object target, String name, Class<T> type) {
    Class<?> current = target.getClass();
    while (current != null) {
      try {
        Field f = current.getDeclaredField(name);
        f.setAccessible(true);
        Object value = f.get(target);
        return value == null ? null : type.cast(value);
      } catch (NoSuchFieldException notHere) {
        current = current.getSuperclass();
      } catch (IllegalAccessException denied) {
        throw new IllegalStateException("读不到 " + name + "（测试夹具需要它）", denied);
      }
    }
    throw new IllegalStateException("ChatService 上没有字段 " + name + " —— 夹具需要跟着改");
  }

  /** 多跳判定用的假模型端口：一个模型都没配 ⇒ 判定不可用 ⇒ 停止迭代（不调任何模型）。 */
  private static final class SilentChat implements ChatPort {

    @Override
    public Optional<UUID> pickChatModel() {
      return Optional.empty();
    }

    /**
     * ★ 走到这里说明有代码以为"有模型可用"——多跳判定只问 {@link #pickChatModel()}。
     *
     * <p>抛而不是返回空串：返回空串会让"没模型"伪装成"模型说了句空话"，而这两件事在日志里必须分得开。
     */
    @Override
    public String complete(UUID modelId, String prompt, Duration timeout) {
      throw new UnsupportedOperationException("测试夹具没有对话模型 —— 调用方应先问 pickChatModel()");
    }

    @Override
    public <T> T completeAs(UUID modelId, String prompt, Class<T> type, Duration timeout) {
      throw new UnsupportedOperationException("测试夹具没有对话模型 —— 调用方应先问 pickChatModel()");
    }
  }
}

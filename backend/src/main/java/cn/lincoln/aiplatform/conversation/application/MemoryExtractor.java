package cn.lincoln.aiplatform.conversation.application;

import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import cn.lincoln.aiplatform.conversation.domain.MemoryLevel;
import cn.lincoln.aiplatform.platform.model.ChatPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 从一轮对话里抽记忆（{@code 0.9.0 · #296}，规则见 {@code 15-长期记忆设计.md §5}）。
 *
 * <h3>★ 只抽三类，别的都不抽</h3>
 *
 * <ul>
 *   <li><strong>用户说自己的事实</strong>（"我在财务部""我们用的是 2021 版制度"）⇒ {@link MemoryKind#FACT} / USER 层；
 *   <li><strong>用户的纠正</strong>（"不是 3 天，是 5 个工作日"）⇒ {@link MemoryKind#CONSTRAINT}，重要度直接给 5：
 *       纠正一旦丢了，下次还会答错同一件事；
 *   <li><strong>用户的长期目标</strong>（"我们下季度要把这个流程上线"）⇒ {@link MemoryKind#GOAL} / CROSS_SESSION 层。
 * </ul>
 *
 * <p>★ <strong>不抽"这次问的问题"</strong>：问题属于会话消息，存成记忆会让下一次对话把上一轮的问句当成"你记得的事"。 这条边界是 {15 §1}
 * 第一条硬约束（记忆不是消息副本）的直接应用。
 *
 * <h3>★ 失败一律返回空表</h3>
 *
 * <p>抽取是在对话结束之后跑的旁路任务，模型挂了、JSON 坏了、超时了都<strong>不该影响用户已经看到的回答</strong>。 失败打 WARN 并说清是哪一步 ——
 * 静默吞掉会让"记忆里怎么一直没东西"没有任何线索。
 */
@Component
public class MemoryExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(MemoryExtractor.class);

  /** 抽取的时间预算（旁路任务，用户已经拿到回答了，给足一点）。 */
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  /** 一轮对话最多抽几条。 */
  private static final int MAX_PER_TURN = 3;

  /** 内容长度上限（与实体一致）。 */
  private static final int MAX_CONTENT = 2000;

  private final ChatPort chat;

  public MemoryExtractor(ChatPort chat) {
    this.chat = chat;
  }

  /**
   * 抽一轮对话的记忆。
   *
   * @param userId 用户
   * @param conversationId 会话
   * @param userMessageId 本轮用户消息 id（记进 {@code source_message_ids}）
   * @param question 用户这轮说的话
   * @param answer 助手这轮的回答
   * @return 待写入的草稿（可能为空表）
   */
  public List<MemoryService.MemoryDraft> extract(
      UUID userId, UUID conversationId, UUID userMessageId, String question, String answer) {
    if (question == null || question.isBlank()) {
      return List.of();
    }
    Optional<UUID> model = chat.pickChatModel();
    if (model.isEmpty()) {
      LOG.warn("没有可用的对话模型，本轮不抽记忆（抽取是旁路任务，不影响已给出的回答）");
      return List.of();
    }
    try {
      Extraction raw =
          chat.completeAs(model.get(), prompt(question, answer), Extraction.class, TIMEOUT);
      if (raw == null || raw.memories() == null) {
        return List.of();
      }
      List<MemoryService.MemoryDraft> drafts = new ArrayList<>();
      for (Item item : raw.memories()) {
        if (item == null || item.content() == null || item.content().isBlank()) {
          continue;
        }
        MemoryKind kind = MemoryKind.of(item.kind());
        int importance = Math.max(1, Math.min(5, item.importance()));
        MemoryLevel level =
            kind == MemoryKind.GOAL
                ? MemoryLevel.CROSS_SESSION
                : (kind == MemoryKind.CONSTRAINT ? MemoryLevel.USER : MemoryLevel.USER);
        drafts.add(
            new MemoryService.MemoryDraft(
                userId,
                level,
                level.conversationScoped() ? conversationId : null,
                kind,
                truncate(item.content()),
                new UUID[] {userMessageId},
                importance,
                MemoryService.MemoryDraft.DEFAULT_CONFIDENCE,
                item.dedupKey()));
        if (drafts.size() >= MAX_PER_TURN) {
          break;
        }
      }
      return drafts;
    } catch (RuntimeException e) {
      LOG.warn("抽取记忆失败（这一轮不留记忆）：{}", e.toString());
      return List.of();
    }
  }

  /**
   * 拼提示词。
   *
   * @param question 用户问题
   * @param answer 助手回答
   * @return 提示词
   */
  private static String prompt(String question, String answer) {
    return """
    从下面这一轮对话里抽取"值得长期记住的用户信息"。只抽三类：
    1. FACT：用户说的关于自己的事实（部门、职务、公司、用的制度版本、正在做的项目名）；
    2. CONSTRAINT：用户对答案的纠正、或者明确说"必须/不能怎样"；
    3. GOAL：用户说的长期目标（跨这次对话之后还要做的事）。

    不要抽：
    - 用户这次问的问题本身；
    - 助手说的话；
    - "用户问了一个问题"这种元信息；
    - 任何你不确定的信息（宁可不记）。

    每条 content 用一句话陈述，主语写"用户"，不要抄原文长句。
    kind 只能是 FACT / CONSTRAINT / GOAL。
    importance 1-5：纠正类给 5，明确的事实给 3-4，目标给 3。
    dedupKey 给一个稳定的机器可读键（如 "dept-财务部"），同一条信息下次抽到必须是同一个键。

    用户：%s

    助手：%s
    """
        .formatted(clip(question), clip(answer));
  }

  /**
   * @param text 原文
   * @return 截到 800 字（提示词里不需要全文）
   */
  private static String clip(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= 800 ? text : text.substring(0, 800) + "…";
  }

  /**
   * @param text 内容
   * @return 截到 {@link #MAX_CONTENT}
   */
  private static String truncate(String text) {
    String trimmed = text.strip();
    return trimmed.length() <= MAX_CONTENT ? trimmed : trimmed.substring(0, MAX_CONTENT - 1) + "…";
  }

  /**
   * 模型返回的抽取结果。
   *
   * @param memories 抽到的条目
   */
  public record Extraction(List<Item> memories) {}

  /**
   * 一条抽取结果。
   *
   * @param kind 种类（FACT / CONSTRAINT / GOAL）
   * @param content 一句话陈述
   * @param importance 重要度 1-5
   * @param dedupKey 去重键
   */
  public record Item(String kind, String content, int importance, String dedupKey) {}
}

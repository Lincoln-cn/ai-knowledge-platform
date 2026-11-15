package cn.lincoln.aiplatform.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 用户画像（{@code #296}，迁移 {@code V101}，表 {@code cv_user_profile}，一人一行）。
 *
 * <h3>★ 为什么单独建表，而不是"level = persona 的一条记忆"</h3>
 *
 * <p>画像的字段是<strong>结构化的</strong>（语气、领域、禁忌、摘要），召回时是<strong>整段注入</strong>；而 persona 层的记忆是一条条可增删的事实。
 * 硬把画像做成一行文本，就只能用一个字段冒充四个结构，而且每次重算都要"先删那一行再写一行" —— 中间失败画像就空了。 单独成表后重算是<strong>一次
 * upsert</strong>，原子且可重跑。
 *
 * <h3>★ 画像里的每一句都要能指出来源</h3>
 *
 * <p>{@link #sourceMemoryIds} 是画像的可追溯性：重算时靠它判断"这条画像是不是还成立"。没有它，画像就是一个用户既不知道、 也改不了的黑箱。
 */
@Entity
@Table(name = "cv_user_profile")
public class UserProfile {

  /** 摘要的字数上限（注入时整段进 prompt，不能无限长）。 */
  public static final int MAX_SUMMARY_LENGTH = 600;

  @Id
  @Column(name = "user_id")
  private UUID userId;

  /** 表达倾向（"先给结论，再给依据"）。 */
  @Column(name = "tone", length = 512)
  private String tone;

  /** 关注领域。 */
  @Column(name = "domains")
  private String[] domains;

  /** 禁忌（"不要用英文缩写"）。 */
  @Column(name = "taboos")
  private String[] taboos;

  /** 一段话的画像（注入时用它，不给模型看上面三个字段）。 */
  @Column(name = "summary")
  private String summary;

  /** 这句话的每一段来自哪几条记忆。 */
  @Column(name = "source_memory_ids")
  private UUID[] sourceMemoryIds;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA 用。 */
  protected UserProfile() {}

  /**
   * 造一份新画像。
   *
   * @param userId 用户 id
   * @param now 当前时间
   * @return 空画像（字段全空，表示"还没有足够信息"）
   */
  public static UserProfile empty(UUID userId, Instant now) {
    UserProfile profile = new UserProfile();
    profile.userId = userId;
    profile.domains = new String[0];
    profile.taboos = new String[0];
    profile.sourceMemoryIds = new UUID[0];
    profile.updatedAt = now;
    return profile;
  }

  /**
   * 重算画像（做梦里的一个动作）。
   *
   * @param newTone 语气倾向（可为 {@code null}）
   * @param newDomains 关注领域（可为 {@code null}）
   * @param newTaboos 禁忌（可为 {@code null}）
   * @param newSummary 摘要（超长截断；{@code null} 表示"这次算不出结论"）
   * @param sources 来源记忆 id
   * @param now 当前时间
   */
  public void recompute(
      String newTone,
      List<String> newDomains,
      List<String> newTaboos,
      String newSummary,
      List<UUID> sources,
      Instant now) {
    this.tone = blankToNull(newTone);
    this.domains = newDomains == null ? new String[0] : newDomains.toArray(new String[0]);
    this.taboos = newTaboos == null ? new String[0] : newTaboos.toArray(new String[0]);
    this.summary = truncate(blankToNull(newSummary));
    this.sourceMemoryIds = sources == null ? new UUID[0] : sources.toArray(new UUID[0]);
    this.updatedAt = now;
  }

  /**
   * 用户手动改画像（页面上可编辑）。
   *
   * @param newTone 语气
   * @param newDomains 领域
   * @param newTaboos 禁忌
   * @param newSummary 摘要
   * @param now 当前时间
   */
  public void edit(
      String newTone,
      List<String> newDomains,
      List<String> newTaboos,
      String newSummary,
      Instant now) {
    recompute(newTone, newDomains, newTaboos, newSummary, sourceIds(), now);
  }

  /**
   * @return 是否有可以被注入的内容
   */
  public boolean hasContent() {
    return summary != null && !summary.isBlank();
  }

  /**
   * @return 注入用的整段文字（没有内容时给空串，调用方据此跳过）
   */
  public String injectableText() {
    return hasContent() ? summary : "";
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.trim();
  }

  private static String truncate(String text) {
    if (text == null) {
      return null;
    }
    return text.length() <= MAX_SUMMARY_LENGTH ? text : text.substring(0, MAX_SUMMARY_LENGTH) + "…";
  }

  private static List<UUID> toList(UUID[] array) {
    return array == null ? List.of() : Arrays.asList(array);
  }

  /**
   * @return 用户 id
   */
  public UUID userId() {
    return userId;
  }

  /**
   * @return 语气倾向
   */
  public String tone() {
    return tone;
  }

  /**
   * @return 关注领域
   */
  public List<String> domainList() {
    return domains == null ? List.of() : Arrays.asList(domains);
  }

  /**
   * @return 禁忌
   */
  public List<String> taboosList() {
    return taboos == null ? List.of() : Arrays.asList(taboos);
  }

  /**
   * @return 画像摘要
   */
  public String summary() {
    return summary;
  }

  /**
   * @return 来源记忆 id
   */
  public List<UUID> sourceIds() {
    return toList(sourceMemoryIds);
  }

  /**
   * @return 更新时间
   */
  public Instant updatedAt() {
    return updatedAt;
  }
}

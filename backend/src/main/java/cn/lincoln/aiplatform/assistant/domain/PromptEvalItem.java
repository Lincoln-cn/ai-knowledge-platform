package cn.lincoln.aiplatform.assistant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;

/**
 * 评测集里的一道题。ROADMAP §11.63.3。
 *
 * <p>★ {@code ordinal} 是显式存的，不靠 id 或插入时间排序： 结果表要按题的原始顺序看 —— "第 3 题"在两次运行里必须是同一道题， 否则"上一次第 3
 * 题是更好的"这句话就无法追溯。
 */
@Entity
@Table(name = "as_prompt_eval_item")
public class PromptEvalItem {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "set_id", nullable = false)
  private UUID setId;

  @Column(name = "ordinal", nullable = false)
  private int ordinal;

  @Column(name = "question", nullable = false, length = 1000)
  private String question;

  /**
   * 这道题的机械断言定义（JSON 数组，见 {@link PromptAssertions}）。
   *
   * <p>★ 存的是 JSON 文本而不是 jsonb：判定与展示都只按"这道题"整块读写，没有一处需要按断言内容做库侧查询 （理由写在
   * `V115__as_prompt_eval_assertions.sql` 的注释里）。
   *
   * <p>★ 空数组 = 没设断言，与"设了断言且全过"是两件事 —— 界面上必须分开显示。
   */
  @Column(name = "assertions", nullable = false)
  private String assertions = "[]";

  protected PromptEvalItem() {}

  public PromptEvalItem(UUID id, UUID setId, int ordinal, String question) {
    this.id = id;
    this.setId = setId;
    this.ordinal = ordinal;
    this.question = question;
  }

  public List<PromptAssertions.Def> assertions() {
    return PromptAssertions.parseDefs(assertions);
  }

  /** 整张断言表替换（增删都走这里，不做单条增删：一次编辑后的形状才是一致的那一份）。 */
  public void replaceAssertions(List<PromptAssertions.Def> defs) {
    this.assertions = PromptAssertions.writeDefs(defs);
  }

  public UUID id() {
    return id;
  }

  public UUID setId() {
    return setId;
  }

  public int ordinal() {
    return ordinal;
  }

  public String question() {
    return question;
  }
}

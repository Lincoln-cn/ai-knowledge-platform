package cn.lincoln.aiplatform.retrieval.domain;

import java.util.List;

/**
 * 一次"查询理解"的产物（{@code 10 §3.3.1}）—— M 个子问题，每个带 N 条查询。
 *
 * <p>它<strong>不是</strong>模型的原始输出，而是"解析 + 校验过的结果"：模型会给什么形状没人能保证， 而下游（M×N 路检索）需要的是一个确定的形状。两者之间的那道关就是
 * {@link QueryUnderstandingParser}。
 *
 * @param subQuestions 子问题（至少一条 —— 空的情况在解析阶段已经被降级掉了）
 */
public record QueryUnderstanding(List<SubQuestion> subQuestions) {

  /**
   * 一个子问题及其查询。
   *
   * @param question 这个子问题的<strong>独立问题</strong>（引用与上下文标注用它，{@code 10 §3.3.4}）
   * @param queries 检索用的查询（1..queryCount 条，已去重、已截断）
   */
  public record SubQuestion(String question, List<String> queries) {}

  /** 单意图、单查询 —— 也就是"查询理解没起作用"时的形状（原始问题单路，D4）。 */
  public static QueryUnderstanding single(String question) {
    return new QueryUnderstanding(List.of(new SubQuestion(question, List.of(question))));
  }

  /** 实际要跑的查询条数（= 检索路数的基数，{@code 10 §6.2} 第 4 项）。 */
  public int totalQueries() {
    return subQuestions.stream().mapToInt(sub -> sub.queries().size()).sum();
  }

  public int subQuestionCount() {
    return subQuestions.size();
  }
}

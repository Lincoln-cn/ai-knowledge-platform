package cn.lincoln.aiplatform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.assistant.application.AssistantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 新建应用检索参数的缺省口径。
 *
 * <p>★ 为什么值得一条独立测试：这两个开关都在**问答主链路上加一次大模型调用**， 一旦缺省被误改成"开着"，症状不是报错而是**首字变慢**（且慢得很像"模型慢"），
 * 排查时最容易被归到错误的层。2026-11-05 就发生过一次：{@code defaults()} 里的 {@code queryCount} 照抄了知识库侧的列默认值 3 ⇒
 * 每个新建应用都默认开查询理解 ⇒ 真栈上桩多收一条 chat 请求，`check_public_share_e2e` 的"提示词进了上游 prompt"
 * 读到的是**改写器**那条请求体，于是两条判据同时红。
 */
class AssistantRetrievalDefaultsTest {

  @Test
  @DisplayName("新建应用默认不改写、不多跳 —— 这两个都会在问答主链路上多要一次大模型调用")
  void aNewAssistantDoesNotSpendExtraModelCallsByDefault() {
    AssistantService.RetrievalDraft draft = AssistantService.RetrievalDraft.defaults();

    assertThat(draft.queryCount())
        .as("查询理解条数的缺省必须是 0（不改写）：本机 1.5B 实测一次 3.7–4.2 秒，而首字基线只有 0.9 秒")
        .isZero();
    assertThat(draft.multiHopEnabled()).as("多跳的缺省必须是 false：第一轮不充分时再检一轮，最差 +7.14 秒").isFalse();
  }

  @Test
  @DisplayName("其余检索参数保留原来的缺省，别顺手改掉")
  void theOtherRetrievalDefaultsStayAsTheyWere() {
    AssistantService.RetrievalDraft draft = AssistantService.RetrievalDraft.defaults();

    assertThat(draft.mode()).isEqualTo("HYBRID");
    assertThat(draft.threshold()).isEqualTo(0.6);
    assertThat(draft.topN()).isEqualTo(5);
    assertThat(draft.noRefStrategy()).isEqualTo("REFUSE");
    assertThat(draft.maxSubQuestions()).isEqualTo(3);
    assertThat(draft.maxHops()).isEqualTo(2);
  }
}

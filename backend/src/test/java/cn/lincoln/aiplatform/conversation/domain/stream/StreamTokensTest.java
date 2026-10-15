package cn.lincoln.aiplatform.conversation.domain.stream;

import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.DURATION_MS;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.assembler;
import static cn.lincoln.aiplatform.conversation.domain.stream.StreamFixture.refs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.conversation.domain.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ★★ <strong>判据④：{@code tokens} 汇总</strong> —— 口径是"该次问答<strong>全部</strong>模型调用的总和" （{@code 10
 * §8.4}），不是"最后那次生成"。
 *
 * <h3>为什么这条账必须由领域层汇总</h3>
 *
 * <p>策略层上线后，一次问答会调用模型 <strong>三次以上</strong>：查询理解（改写/扩展/分解）、多跳判定、生成。 若 {@code done.tokens}
 * 只报生成那一次，{@code analytics} 的 token 统计与成本口径会<strong>全部偏低</strong>，
 * 而偏低幅度<strong>随策略开关变化</strong>（关掉多跳就少一次调用）—— 那是最难查的一类账： 它不会报错，只会让"上个月成本怎么涨了"变成一个无法归因的问题。
 *
 * <p>★ 所以累加只有一个入口（{@link StreamAssembler#addUsage} → {@link TokenUsage#plus}）， 且"一次调用都没有"的口径是
 * {@link TokenUsage#zero()} 而<strong>不是 {@code null}</strong> （契约里 {@code tokens} 是 {@code done}
 * 的必填字段，{@code null} 到前端会静默变成 {@code undefined}）。
 */
class StreamTokensTest {

  /**
   * ★★ 三次调用各自报一次用量 ⇒ {@code done.tokens} 是三者之和。
   *
   * <p>★ 断言的是 {@code done} 事件上的那个 payload（而不是 {@code assembler.tokens()}）：
   * 真正发出去的是事件里的那一份，两者若来自不同的字段，本用例会红。
   *
   * <p>★★ <strong>本用例是域层单测：这三笔用量由这里<u>直接喂入</u></strong>（{@link StreamAssembler#addUsage}）。
   * 它证明的是"累加口只有一个、且总和进 {@code done}"——<strong>它不证明"真实链路会喂"</strong>。 （{@code #142} ④
   * 之前这里写的是"这一次在真实链路上发生在检索之前"，那是一句<strong>把未实现当成已实现</strong>的前提： 当时 {@code addUsage} 在 {@code
   * backend/src/main} 是<strong>零调用点</strong>。）
   *
   * <p>★★ 生产上的喂入点与它的真栈读数在<strong>别处</strong>： {@code ChatStreamPort.Callback#onUsage} → {@code
   * ChatService.Run#onUsage} → {@link StreamAssembler#addUsage}， 由真栈用例 {@code
   * ChatStreamUsageHttpTest} 钉住（它读 HTTP 上 {@code done} 帧的三字段与库里那两列， 逐字等于假模型上报的分量）。★ 查询理解 /
   * 多跳判定那两次调用<strong>今天还没有接进问答链路</strong> （属检索策略那一批），所以这里前两笔是"将来的形状"：等它们接进来，累加口径一个字都不必改。
   */
  @Test
  void tokensSumEveryModelCallNotJustTheGeneration() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());

    // ① 查询理解（改写 + 扩展）那一笔 —— ★ 直接喂入：它今天的生产调用点还不存在（见方法注释）
    assembler.addUsage(new TokenUsage(120, 40));
    // ② 多跳判定那一笔 —— 同上（真栈上这两笔接进来时，喂的仍是这同一个 addUsage）
    assembler.addUsage(new TokenUsage(300, 10));
    // ③ 生成那一笔（真栈上由 ChatStreamPort.Callback#onUsage 喂进来，就在 onEnd 之前）
    assembler.delta("根据文档规定，");
    assembler.addUsage(new TokenUsage(820, 340));

    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    TokenUsage tokens = assembler.done().tokens();
    assertThat(tokens.prompt()).as("提示词分量 = 120 + 300 + 820").isEqualTo(1240);
    assertThat(tokens.completion()).as("生成分量 = 40 + 10 + 340").isEqualTo(390);
    assertThat(tokens.total()).as("total 由两个分量导出（契约里是同一个 payload 的第三个字段）").isEqualTo(1630);
  }

  /**
   * 一次调用都没报用量（例如全部命中缓存、或纯本地判定）⇒ {@code zero()}，不是 {@code null}。
   *
   * <p>★ 这条与上一条是<b>一对</b>：只有"求和正确"没有"空和是 0"，一个把 {@code tokens} 留成 {@code null}
   * 的实现会在"没有调用"这条路径上让前端读到 {@code NaN}。
   */
  @Test
  void noReportedUsageIsZeroNotMissing() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.delta("缓存命中。");
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThat(assembler.done().tokens())
        .as("空和的单位元是 0，不是 null —— 契约里 tokens 是必填")
        .isEqualTo(TokenUsage.zero());
    assertThat(assembler.done().tokens().total()).isZero();
  }

  /**
   * {@code addUsage(null)} = 这一路没有报用量 ⇒ <strong>不变</strong>，不清零、不抛。
   *
   * <p>★ 与 {@code Conversation#raiseClassification(null)} 同一条口径："没有值"是正常路径，
   * 对它报错只会逼调用方在每一处先判一次空（口径散到各读点），而"当成 0"则会让先前的累计被悄悄抹掉。
   */
  @Test
  void nullUsageKeepsTheAccumulatedTotal() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.addUsage(new TokenUsage(100, 20));
    assembler.addUsage(null);
    assembler.delta("正文");
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThat(assembler.done().tokens())
        .as("null 是'这一路没报'，不是'归零'")
        .isEqualTo(new TokenUsage(100, 20));
  }

  /**
   * ★ 停止 / 失败路径上，<strong>已经发生的调用仍然要计入</strong>。
   *
   * <p>为什么值得单列：三条异常路径最容易被写成"收尾时构造一个零用量"（因为"这次的生成没成功"）。 但查询理解与多跳判定是<strong>真的调用过</strong>的 —— 那些
   * token 是真花的钱。把失败路径的用量归零， 会让"越失败越省钱"这种荒谬的读数出现在成本表上。
   */
  @ParameterizedTest
  @EnumSource(StreamTerminationPath.class)
  void partialUsageSurvivesEveryTerminationPath(StreamTerminationPath path) {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.addUsage(new TokenUsage(120, 40));
    assembler.delta("根据");

    assembler.finish(path, DURATION_MS);

    assertThat(assembler.done().tokens())
        .as("路径 %s：已经花掉的 token 不许因为收尾方式而消失", path)
        .isEqualTo(new TokenUsage(120, 40));
  }

  /** 负 token ⇒ 非法输入当场抛（token 是只增的计数器，负数只能是算错了）。 */
  @Test
  void negativeTokensAreRefused() {
    assertThatThrownBy(() -> new TokenUsage(-1, 0))
        .as("负数 token 只能来自'减了一下'的实现")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为负");
  }

  /** 收尾之后再报用量也是非法（一条流一旦终态，账就封了）。 */
  @Test
  void usageAfterDoneIsRefused() {
    StreamAssembler assembler = assembler();
    assembler.begin();
    assembler.references(refs());
    assembler.finish(StreamTerminationPath.COMPLETE, DURATION_MS);

    assertThatThrownBy(() -> assembler.addUsage(new TokenUsage(1, 1)))
        .as("done 之后还能改 tokens，就等于那份终态账可以被事后改写")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("done 之后");
  }
}

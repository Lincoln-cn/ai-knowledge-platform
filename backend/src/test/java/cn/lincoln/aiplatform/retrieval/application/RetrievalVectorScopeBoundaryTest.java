package cn.lincoln.aiplatform.retrieval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.lincoln.aiplatform.platform.model.EmbeddingPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankPort;
import cn.lincoln.aiplatform.platform.retrieval.RerankProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalBudgetProperties;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalScope;
import cn.lincoln.aiplatform.platform.retrieval.SegmentSearchPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * ★★ 向量路对<strong>会话域</strong>的处置：<strong>显式摘掉并留痕</strong>，不是"悄悄摘掉"、也不是"查不到所以报错"（{@code #31} 判据 1
 * 的另一半；{@code #378} 收口时改过一次口，见第一条判据的注释）。
 *
 * <h3>这条边界的形状（本批的裁定）</h3>
 *
 * <p>会话临时的分段没有 {@code kb_knowledge_base} 行 ⇒ 它的向量模型继承<strong>平台默认</strong>（{@code 13 §11.2}），
 * 而"平台默认向量模型"那一格今天<strong>无处解析</strong>。所以本批明确：<strong>会话附件暂不参与向量路</strong> （只走全文一路）。表达它的两个具名值在
 * {@link RetrievalScope} 上：
 *
 * <ul>
 *   <li>{@link RetrievalScope#CONVERSATION_SCOPE_IN_VECTOR_PATH} —— {@code false} = 不收；
 *   <li>{@link RetrievalScope#hasConversationScope()} —— 这一批域里有没有会话临时。
 * </ul>
 *
 * <p>★ 装配点（{@code RetrievalService#vectorSearch} 开头）用这两个值做判定，于是"会话域进了向量路"这件事
 * 是<strong>一条可以逐字读到的判定</strong>，而不是"没有库行 ⇒ 模型查不到 ⇒ 顺手抛了个错" （后者会让同一条边界在别人的下一次重构里悄悄消失）。
 *
 * <h3>★★ 它为什么能红（这就是"把边界去掉必须红"的读数）</h3>
 *
 * <table>
 *   <tr><th>改法</th><th>读数</th></tr>
 *   <tr><td>把 {@code vectorSearch} 开头那条判定整段删掉（回到"靠异常碰巧挡住"）</td>
 *       <td>★ {@link #conversationScopeIsRefusedOnTheVectorPathWithADeclaredBoundary()} 红：抛出来的会是
 *           "这些知识库没有可用的向量模型"（下游那一条），而出处的<strong>那一句</strong>没了</td></tr>
 *   <tr><td>把 {@code CONVERSATION_SCOPE_IN_VECTOR_PATH} 改成 {@code true}（放开边界）</td>
 *       <td>★ 同上：判定不再拦人 ⇒ 落到下游那条错误上</td></tr>
 *   <tr><td>把会话域"<b>悄悄</b>"摘掉再查（不报错、也不留痕，只是少看见）</td>
 *       <td>★ 第一条判据红在"日志里没有那句留痕"——本批选的是<b>显式摘掉 + 留痕</b>，两者差的就是那句话</td></tr>
 * </table>
 *
 * <p>★ 本类<strong>不要库、不起 Spring</strong>：判定发生在"问模型"之前，所以下面给的是 {@code null} 依赖 —— 只要判定还在，这些 {@code
 * null} 永远不会被碰到（而它们被碰到，本身就是"边界没了"的读数）。
 *
 * <p>★ 而"会话域的 SQL 谓词在向量路上成立吗"是<strong>另一件事</strong>，由 {@code
 * RetrievalDomainScopeTest#conversationDomainIsAlsoReachableOnTheVectorPath} 在真 PG 上钉着 ——
 * 端口**能**按三域过滤，是服务层**今天不收**它；两者都成立、且都不是对方的替代。
 */
class RetrievalVectorScopeBoundaryTest {

  private static final UUID CONVERSATION = UUID.fromString("2a000000-0000-4000-8000-000000000001");
  private static final UUID ME = UUID.fromString("2d000000-0000-4000-8000-000000000001");
  private static final UUID SHARED_KB = UUID.fromString("2e000000-0000-4000-8000-000000000001");
  private static final UUID VECTOR_MODEL = UUID.fromString("2f000000-0000-4000-8000-000000000001");
  private static final UUID SEGMENT = UUID.fromString("2f100000-0000-4000-8000-000000000001");

  /**
   * ★★ 判据：带会话域的检索范围在向量路上被<strong>摘掉会话域之后照常检索</strong>，并且必须留一条痕。
   *
   * <p>★ 这条判据在 {@code #378} 收口时改过一次口（原来是"必须抛 400"）。改的理由是可核对的：问答链路的 {@code conversationId}
   * <strong>每一轮都有值</strong>（它只说明"这是一次会话内提问"，不代表本轮带了附件），按"拒绝"处理会把每一轮会话内问答整体变成零引用 —— 实测 9
   * 条对话测试一起红，而它们与这条边界毫无关系。会话附件的既定口径不变：它<strong>只走全文一路</strong>。
   *
   * <p>★★ 而"悄悄摘掉"这条改法仍然要防 —— 所以这里不是断言"不抛"就完事，而是断言两件事：
   *
   * <ol>
   *   <li>真正送进向量查询的那个范围里<b>没有</b>会话域（留着它，会话分段就会拿错模型/维度表）；
   *   <li>日志里有一句可读的留痕 —— 没有它，"会话附件少看见几条"与"确实没命中"在读数上完全一样 （本项目头号病）。
   * </ol>
   */
  @Test
  void conversationScopeIsNarrowedOnTheVectorPathAndLeavesATrace() {
    RetrievalScope scope = new RetrievalScope(List.of(SHARED_KB), null, CONVERSATION, ME);
    RecordingSearch search = new RecordingSearch();
    ListAppender<ILoggingEvent> logs = attachLogs();

    List<SegmentSearchPort.Scored> hits = service(search).vectorSearch("合同违约金上限", scope, 5);

    assertThat(hits).as("知识库那一路照常检索 —— 摘掉会话域不等于把整条路关掉").isNotEmpty();
    assertThat(search.scopeSeen.hasConversationScope())
        .as("★★ 送进向量查询的范围里不许再有会话域：会话分段的向量模型 = 平台默认，而那一格今天无处解析")
        .isFalse();
    assertThat(search.scopeSeen.sharedKbIds()).as("共用库一支必须原样保留").containsExactly(SHARED_KB);
    assertThat(logs.list)
        .as("★ 收窄必须留痕：没有这句日志，「会话附件少看见几条」与「确实没命中」长得一模一样")
        .anyMatch(event -> event.getFormattedMessage().contains("会话附件不参与向量一路"));

    detachLogs(logs);
  }

  /**
   * ★ 边界只在"域里真的含会话临时"时生效：只带共用库域的范围仍然照常往下走。
   *
   * <p>反证的价值：如果判定写成"只要有范围就拒绝"（或常量被误读成"向量路整个关掉"），这条会红 —— 而那意味着库里原有的向量/混合检索全被这条边界误伤，属于比"少看见"更响的故障。
   *
   * <p>★ 这里**故意**给 {@code null} 的检索端口：共用库域能走到"问模型"这一步（也就是越过了边界）， 就会在 {@code segmentSearch} 上抛
   * {@link NullPointerException} —— 那正是"边界没有误伤它"的读数。
   */
  @Test
  void aScopeWithoutAConversationStillProceedsPastTheBoundary() {
    RetrievalScope sharedOnly = RetrievalScope.shared(List.of(SHARED_KB));

    assertThatThrownBy(() -> service().vectorSearch("合同违约金上限", sharedOnly, 5))
        .as("★ 不带会话域的范围必须能越过这条边界（它只针对会话临时，不是把向量路整个关掉）")
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * ★ 常量的当前值 + "域里有没有会话临时"这条判定的两个方向。
   *
   * <p>它是给下一位读者的<strong>路标</strong>：放开边界的触发条件写在 {@link
   * RetrievalScope#CONVERSATION_SCOPE_IN_VECTOR_PATH} 的 javadoc 上
   * （"平台默认向量模型"定清并落地），而这条断言让"今天还是不收"这件事有读数 —— 谁把它改成 {@code
   * true}，本类第一条会红，于是那次放开必须是一次<strong>显式改口</strong>。
   */
  @Test
  void theVectorPathStillExcludesTheConversationScopeToday() {
    assertThat(RetrievalScope.CONVERSATION_SCOPE_IN_VECTOR_PATH)
        .as("★★ 今天会话附件不参与向量路（平台默认向量模型那一格无处解析 ⇒ 既嵌不了问题也定不了维度表）。" + "要放开它，先看这个常量的 javadoc 里写的触发条件")
        .isFalse();
    assertThat(
            new RetrievalScope(List.of(SHARED_KB), null, CONVERSATION, ME).hasConversationScope())
        .as("判定的一端：带会话域的范围必须被读成「含会话临时」")
        .isTrue();
    assertThat(RetrievalScope.shared(List.of(SHARED_KB)).hasConversationScope())
        .as("判定的另一端：纯库域的范围不许被读成「含会话临时」（否则它会误伤全部向量/混合检索）")
        .isFalse();
  }

  /**
   * ★ 问答链路专用：{@code withoutConversation()} 必须只摘会话域，不动共用库。
   *
   * <p>它防的是"为了走向量而把整个范围清空"——那会让知识库也一起搜不到，读数看起来只是"零命中"。
   */
  @Test
  void withoutConversationKeepsTheKnowledgeBaseDomain() {
    RetrievalScope scope = new RetrievalScope(List.of(SHARED_KB), null, CONVERSATION, ME);

    RetrievalScope vectorScope = scope.withoutConversation();

    assertThat(vectorScope.hasConversationScope()).as("会话域必须被摘掉").isFalse();
    assertThat(vectorScope.sharedKbIds()).as("共用库一支必须原样保留").containsExactly(SHARED_KB);
    assertThat(vectorScope.conversationId()).isNull();
    assertThat(vectorScope.ownerId()).isNull();
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /**
   * 只装到一个"能跑判定"的程度：{@code segmentSearch} / {@code embeddingPort} 给 {@code null}。
   *
   * <p>★ {@code queryUnderstanding} / {@code multiHop} 也给 {@code null}：本类调用的是包内可见的 {@code
   * vectorSearch}，不经过它们的公开入口 —— 于是这三条断言不需要任何模型桩、也不需要库。
   */
  private static RetrievalService service() {
    return new RetrievalService(
        null,
        null,
        null,
        null,
        new RetrievalBudgetProperties(5000, 5000, 0.6, true, true),
        RerankPort.noop(),
        RerankProperties.defaults());
  }

  /** 记录"真正送进向量查询的那个范围"，它才是这条边界的读数。 */
  private static final class RecordingSearch implements SegmentSearchPort {

    private RetrievalScope scopeSeen;

    @Override
    public List<UUID> visibleKbIds(Collection<UUID> requested) {
      throw new UnsupportedOperationException("本类只验向量路，不解析可见库");
    }

    @Override
    public List<Scored> fullText(TextQuery query) {
      throw new UnsupportedOperationException("本类只验向量路");
    }

    @Override
    public List<Card> load(Collection<UUID> segmentIds) {
      throw new UnsupportedOperationException("本类不需要取卡片");
    }

    @Override
    public Map<UUID, UUID> embeddingModelByKb(Collection<UUID> kbIds) {
      return Map.of(SHARED_KB, VECTOR_MODEL);
    }

    @Override
    public List<Scored> vectorSearch(VectorQuery query) {
      scopeSeen = query.scope();
      return List.of(new Scored(SEGMENT, 0.9));
    }
  }

  private static RetrievalService service(SegmentSearchPort search) {
    EmbeddingPort embedding = (modelId, texts) -> List.of(new float[] {0.1f, 0.2f});
    return new RetrievalService(
        search,
        embedding,
        null,
        null,
        new RetrievalBudgetProperties(5000, 5000, 0.6, true, true),
        RerankPort.noop(),
        RerankProperties.defaults());
  }

  private static ListAppender<ILoggingEvent> attachLogs() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    serviceLogger().addAppender(appender);
    return appender;
  }

  private static void detachLogs(ListAppender<ILoggingEvent> appender) {
    serviceLogger().detachAppender(appender);
  }

  private static Logger serviceLogger() {
    return (Logger) LoggerFactory.getLogger(RetrievalService.class);
  }
}

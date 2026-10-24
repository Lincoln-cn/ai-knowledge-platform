package cn.lincoln.aiplatform.modelcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

/**
 * ★ 0.4.5 判据 ⑤：把「框架假设」变成一次能红的断言（{@code #62} / {@code ROADMAP §4.7.3}）。
 *
 * <h3>它断言什么</h3>
 *
 * <ol>
 *   <li>{@link RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT} 在链上<strong>真的可取</strong>（后置 advisor
 *       读到的是检索到的文档，不是 null）；
 *   <li><strong>order 真的生效</strong>：检索 advisor 250 先跑，读取 advisor 300 后跑（300 能看见 250 写进上下文的东西）；
 *   <li><strong>joiner 真的被调用</strong>：我们给的 {@link DocumentJoiner} 被调用恰好一次，且合并结果进了最终 prompt。
 * </ol>
 *
 * <h3>★ 它不证明什么（不要读成"生产已经接了"）</h3>
 *
 * <p>本用例用一个桩 {@link ChatModel}，只验证 Spring AI 的<strong>框架语义</strong>。生产对话端口仍走手写 HTTP（{@code #62}），
 * advisor 链在生产路径上仍然没有挂点 —— 那一条是独立欠账，本用例不冒充它。
 *
 * <p>★ 为什么要引 {@code spring-ai-rag}（且只引 test scope）：这三个断言的对象是官方类；
 * 不引依赖就只能测一个自己实现的同名概念，那种断言永远为真，也永远发现不了升级带来的语义漂移。
 */
class AdvisorChainAssumptionTest {

  @Test
  void documentContextOrderAndJoinerAreFrameworkFacts() {
    List<Document> retrieved =
        List.of(new Document("文档甲：市内交通费每日上限 80 元"), new Document("文档乙：出差住宿需提前审批"));

    AtomicInteger joinCalls = new AtomicInteger();
    DocumentJoiner joiner =
        input -> {
          joinCalls.incrementAndGet();
          return input.values().stream()
              .flatMap(lists -> lists.stream().flatMap(List::stream))
              .toList();
        };
    DocumentRetriever retriever = query -> retrieved;

    RetrievalAugmentationAdvisor rag =
        RetrievalAugmentationAdvisor.builder()
            .documentRetriever(retriever)
            .documentJoiner(joiner)
            .queryAugmenter(ContextualQueryAugmenter.builder().build())
            .order(250)
            .build();

    AtomicReference<Object> contextAt300 = new AtomicReference<>();
    List<Integer> ordersSeen = new CopyOnWriteArrayList<>();
    BaseAdvisor reader =
        new BaseAdvisor() {
          @Override
          public String getName() {
            return "assumption-reader-300";
          }

          @Override
          public int getOrder() {
            return 300;
          }

          @Override
          public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            ordersSeen.add(300);
            contextAt300.set(request.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT));
            return request;
          }

          @Override
          public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            return response;
          }
        };

    AtomicReference<Prompt> promptSeen = new AtomicReference<>();
    ChatModel stubModel =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            promptSeen.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("好的"))));
          }
        };

    String answer =
        ChatClient.builder(stubModel)
            .defaultAdvisors(rag, reader)
            .build()
            .prompt()
            .user("市内交通费上限是多少？")
            .call()
            .content();

    assertThat(answer).isEqualTo("好的");
    assertThat(rag.getOrder()).as("检索 advisor 的 order 必须是 250（11 §9 的约定）").isEqualTo(250);
    assertThat(ordersSeen).as("order 300 的读取 advisor 真的在链上跑过").containsExactly(300);

    assertThat(contextAt300.get())
        .as("★ DOCUMENT_CONTEXT 在 250 之后可取，且是检索到的文档")
        .isInstanceOf(List.class);
    List<?> contextDocuments = (List<?>) contextAt300.get();
    assertThat(contextDocuments).hasSize(2);
    assertThat(contextDocuments).allSatisfy(item -> assertThat(item).isInstanceOf(Document.class));

    assertThat(joinCalls.get()).as("★ DocumentJoiner 必须真被调用（且只调一次）").isEqualTo(1);

    assertThat(promptSeen.get()).as("模型确实被调用").isNotNull();
    assertThat(promptSeen.get().getContents())
        .as("joiner 的合并结果真的进了最终 prompt")
        .contains("市内交通费每日上限 80 元")
        .contains("出差住宿需提前审批");
  }
}

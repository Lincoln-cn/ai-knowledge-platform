package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 文档状态的「是不是失败态」分类（{@code #358} · 附件失败态误归因的收口）。
 *
 * <p>★ 为什么这条要按「逐个成员」断言而不是只测两个常量：{@code isFailed()} 的用途是「这份内容现在还能不能被检索到」， 加一个新状态时若忘了给它分类， 单测 {@code
 * assertThat(SEGMENT_FAILED.isFailed()).isTrue()} 照样是绿的 —— 而漏掉的那个状态会在问答链路里 变成「知识库里没有相关内容」这句假话。
 *
 * <p>★ 断言写成「失败态的集合恰好等于这两个」：多一个（有人把 {@code PENDING_EMBEDDING} 也算失败 —— 会话域只走
 * 全文，待向量化的分段照样能检索到，算失败就会把能用的附件报成坏的）少一个都会红。
 */
class DocumentStatusClassificationTest {

  @Test
  void exactlyTheTwoPostProcessingFailuresCountAsFailed() {
    assertThat(Arrays.stream(DocumentStatus.values()).filter(DocumentStatus::isFailed))
        .as("失败态必须恰好是「分段失败」与「向量化失败」两个 —— 多一个会误报、少一个会把坏的报成好的")
        .containsExactlyInAnyOrder(DocumentStatus.SEGMENT_FAILED, DocumentStatus.EMBEDDING_FAILED);
  }

  @Test
  void aPendingEmbeddingDocumentIsNotAFailure() {
    // ★ 会话域只走全文一路（RetrievalScope#CONVERSATION_SCOPE_IN_VECTOR_PATH = false）
    //   ⇒ 还没向量化的分段仍然能被全文检索命中，把它算成失败会让用户收到"附件不可用"的假通知。
    assertThat(DocumentStatus.PENDING_EMBEDDING.isFailed()).isFalse();
    assertThat(DocumentStatus.SUCCEEDED.isFailed()).isFalse();
    assertThat(DocumentStatus.PENDING_PARSE.isFailed()).isFalse();
  }
}

package cn.lincoln.aiplatform.retrieval.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.platform.assistant.WorkflowSearchPort;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalMode;
import cn.lincoln.aiplatform.retrieval.application.HitTestQuery;
import cn.lincoln.aiplatform.retrieval.application.HitTestView;
import cn.lincoln.aiplatform.retrieval.application.RetrievalService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 「检索节点」的检索能力适配器。
 *
 * <p>这类适配器的测试要盯的不是"能不能跑"，而是<strong>有没有把参数原样转过去</strong>： 端口签名里 {@code topN} 一旦被写死成 5，节点上那个「Top
 * 5」就成了摆设， 而页面不会报错 —— 用户改了半天以为生效了（`#334` 记的就是这种形状）。
 */
class WorkflowSearchAdapterTest {

  private static final UUID KB = UUID.fromString("05f0a754-6c67-4604-986d-ea8f32d54aa8");

  @Test
  @DisplayName("命中的 docName 与 content 原样映射成片段，顺序按 rank")
  void mapsHitsIntoSnippets() {
    RetrievalService retrieval = mock(RetrievalService.class);
    when(retrieval.hitTest(any()))
        .thenReturn(
            new HitTestView(
                RetrievalMode.HYBRID,
                List.of(
                    item(1, "合同管理制度.pdf", "第三十一条 违约金按日万分之五计算。"),
                    item(2, "采购合同模板.docx", "违约责任：按合同总额的百分之五。")),
                12L,
                null));

    List<WorkflowSearchPort.Snippet> snippets =
        new WorkflowSearchAdapter(retrieval).search(KB, "违约金", 3);

    assertThat(snippets).hasSize(2);
    assertThat(snippets.get(0).docName()).isEqualTo("合同管理制度.pdf");
    assertThat(snippets.get(0).content()).contains("第三十一条");
    assertThat(snippets.get(1).docName()).isEqualTo("采购合同模板.docx");
  }

  @Test
  @DisplayName("知识库、问题、TopN 三样都真的传到了检索引擎")
  void passesTheCallersParametersThrough() {
    RetrievalService retrieval = mock(RetrievalService.class);
    when(retrieval.hitTest(any()))
        .thenReturn(new HitTestView(RetrievalMode.HYBRID, List.of(), 1L, null));

    new WorkflowSearchAdapter(retrieval).search(KB, "违约金怎么算", 7);

    ArgumentCaptor<HitTestQuery> captor = ArgumentCaptor.forClass(HitTestQuery.class);
    verify(retrieval).hitTest(captor.capture());
    HitTestQuery sent = captor.getValue();
    assertThat(sent.kbIds()).containsExactly(KB);
    assertThat(sent.question()).isEqualTo("违约金怎么算");
    // ★ 断 7 而不是断"不等于 5"：上一版把 topN 写死成 5，这条会红
    assertThat(sent.topN()).isEqualTo(7);
  }

  @Test
  @DisplayName("一条都没命中时给空列表，不返回 null —— 端口契约要求调用方能直接遍历")
  void emptyHitsGiveAnEmptyList() {
    RetrievalService retrieval = mock(RetrievalService.class);
    when(retrieval.hitTest(any()))
        .thenReturn(new HitTestView(RetrievalMode.HYBRID, List.of(), 1L, null));

    assertThat(new WorkflowSearchAdapter(retrieval).search(KB, "不存在的问题", 5)).isEmpty();
  }

  private static HitTestView.Item item(int rank, String docName, String content) {
    return new HitTestView.Item(rank, UUID.randomUUID(), "段落标题", docName, "合同库", 0.82, content);
  }
}

package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.platform.knowledge.DocumentExtractPort;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code DOCUMENT_EXTRACT_NODE} 执行器（{@code #333}）。
 *
 * <p>★ 这条测试要钉的不是"能跑通一次"，而是<strong>四种失败各自说得出是哪一种</strong>：没选文档 / docId 不是 UUID / 文档不存在 /
 * 文档还没有正文。把这四种合成一句"解析失败"，用户拿着节点记录没法自救。
 */
class DocumentExtractNodeExecutorTest {

  private static final UUID DOC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID EXECUTION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

  private final FlowState state = new FlowState();

  @Test
  @DisplayName("正常：文档有正文 ⇒ 写进 document_markdown")
  void writesMarkdownIntoState() {
    DocumentExtractPort port =
        portReturning(Optional.of(text("采购合同.docx", "# 采购合同\n\n正文", "SUCCEEDED")));

    new DocumentExtractNodeExecutor(providerOf(port))
        .execute(context(Map.of("docId", DOC_ID.toString())));

    assertThat(state.read(DocumentExtractNodeExecutor.RESULT_KEY)).isEqualTo("# 采购合同\n\n正文");
  }

  @Test
  @DisplayName("没配 docId ⇒ 说是配置问题，不猜默认文档")
  void rejectsMissingDocId() {
    DocumentExtractPort port = portReturning(Optional.of(text("a.docx", "正文", "SUCCEEDED")));

    assertThatThrownBy(
            () -> new DocumentExtractNodeExecutor(providerOf(port)).execute(context(Map.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有选文档");
    assertThat(state.asMap())
        .as("失败时不许往 state 里写半成品")
        .doesNotContainKey(DocumentExtractNodeExecutor.RESULT_KEY);
  }

  @Test
  @DisplayName("docId 不是 UUID ⇒ 说 id 不合法，不说「文档不存在」")
  void rejectsMalformedDocId() {
    DocumentExtractPort port = portReturning(Optional.of(text("a.docx", "正文", "SUCCEEDED")));

    assertThatThrownBy(
            () ->
                new DocumentExtractNodeExecutor(providerOf(port))
                    .execute(context(Map.of("docId", "不是-uuid"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是合法 UUID");
  }

  @Test
  @DisplayName("文档不存在或已删 ⇒ 说文档没了")
  void rejectsUnknownDocument() {
    DocumentExtractPort port = portReturning(Optional.empty());

    assertThatThrownBy(
            () ->
                new DocumentExtractNodeExecutor(providerOf(port))
                    .execute(context(Map.of("docId", DOC_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("文档不存在或已被删除");
  }

  @Test
  @DisplayName("文档在但正文为空 ⇒ 把状态带出来，让用户知道是在等解析还是解析失败")
  void rejectsUnparsedDocument() {
    DocumentExtractPort port = portReturning(Optional.of(text("扫描件.pdf", "", "PENDING_PARSE")));

    assertThatThrownBy(
            () ->
                new DocumentExtractNodeExecutor(providerOf(port))
                    .execute(context(Map.of("docId", DOC_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("扫描件.pdf")
        .hasMessageContaining("PENDING_PARSE");
  }

  @Test
  @DisplayName("解析能力没装配 ⇒ 部署问题，不许当成「文档没正文」")
  void rejectsMissingPort() {
    assertThatThrownBy(
            () ->
                new DocumentExtractNodeExecutor(providerOf(null))
                    .execute(context(Map.of("docId", DOC_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("还没装配");
  }

  @Test
  @DisplayName("端口返回 null ⇒ 视为实现违约，不当成「文档不存在」")
  void rejectsContractViolation() {
    DocumentExtractPort port = portReturning(null);

    assertThatThrownBy(
            () ->
                new DocumentExtractNodeExecutor(providerOf(port))
                    .execute(context(Map.of("docId", DOC_ID.toString()))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("契约要求 Optional.empty()");
  }

  private WorkflowNodeContext context(Map<String, Object> config) {
    return new WorkflowNodeContext(
        EXECUTION_ID, "n-doc", "DOCUMENT_EXTRACT_NODE", "入口输入", state, config);
  }

  private static DocumentExtractPort.DocumentText text(
      String filename, String markdown, String status) {
    return new DocumentExtractPort.DocumentText(filename, markdown, status);
  }

  private static DocumentExtractPort portReturning(
      java.util.Optional<DocumentExtractPort.DocumentText> result) {
    DocumentExtractPort port = mock(DocumentExtractPort.class);
    when(port.textOf(DOC_ID)).thenReturn(result);
    return port;
  }

  /** 只吐一个实例的 {@code ObjectProvider}（Spring 在生产里注的就是它的实现）。 */
  private static ObjectProvider<DocumentExtractPort> providerOf(DocumentExtractPort port) {
    return new ObjectProvider<>() {
      @Override
      public DocumentExtractPort getObject(Object... args) {
        return port;
      }

      @Override
      public DocumentExtractPort getIfAvailable() {
        return port;
      }

      @Override
      public DocumentExtractPort getIfUnique() {
        return port;
      }

      @Override
      public DocumentExtractPort getObject() {
        return port;
      }

      @Override
      public Iterator<DocumentExtractPort> iterator() {
        return List.of(port).iterator();
      }
    };
  }
}

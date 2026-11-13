package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.DocumentStatus;
import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.platform.knowledge.DocumentExtractPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TikaDocumentExtractAdapter}：端口的两条路都得真通 —— 解析（{@code extract}）与取正文（{@code textOf}）。
 *
 * <p>★ 为什么要有这条：{@code #333} 的原设计与实测对不上（{@code kb_document} 里没有原始字节列，只有 {@code
 * parsed_content}）。这条测试钉的是<strong>改完之后的那条路</strong>：正文从库里的 {@code parsed_content}
 * 来，软删的文档按"不存在"处理。反证：把 {@code filter(deletedAt == null)} 去掉，{@code
 * softDeletedDocumentIsTreatedAsMissing} 必红。
 */
class TikaDocumentExtractAdapterTest {

  private static final UUID DOC_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

  private final TikaMarkdownParser parser = mock(TikaMarkdownParser.class);
  private final DocumentRepository documents = mock(DocumentRepository.class);
  private final TikaDocumentExtractAdapter adapter =
      new TikaDocumentExtractAdapter(parser, documents);

  @Test
  @DisplayName("textOf：库里有正文 ⇒ 连文件名与状态一起给出来")
  void textOfReturnsParsedContent() {
    Document stored = document("需求说明书.docx", "# 需求\n\n正文", DocumentStatus.SUCCEEDED);
    when(documents.findById(DOC_ID)).thenReturn(Optional.of(stored));

    Optional<DocumentExtractPort.DocumentText> found = adapter.textOf(DOC_ID);

    assertThat(found).isPresent();
    assertThat(found.get().filename()).isEqualTo("需求说明书.docx");
    assertThat(found.get().markdown()).isEqualTo("# 需求\n\n正文");
    assertThat(found.get().status()).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("textOf：文档不存在 ⇒ empty")
  void missingDocumentGivesEmpty() {
    when(documents.findById(DOC_ID)).thenReturn(Optional.empty());

    assertThat(adapter.textOf(DOC_ID)).isEmpty();
  }

  @Test
  @DisplayName("textOf：软删的文档按不存在处理")
  void softDeletedDocumentIsTreatedAsMissing() {
    Document deleted = document("旧版.docx", "正文", DocumentStatus.SUCCEEDED);
    when(deleted.deletedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
    when(documents.findById(DOC_ID)).thenReturn(Optional.of(deleted));

    assertThat(adapter.textOf(DOC_ID)).isEmpty();
  }

  @Test
  @DisplayName("textOf：还没解析过 ⇒ 正文是空串（不是 null），状态照样带出来")
  void unparsedDocumentKeepsItsStatus() {
    Document pending = document("扫描件.pdf", null, DocumentStatus.PENDING_PARSE);
    when(documents.findById(DOC_ID)).thenReturn(Optional.of(pending));

    DocumentExtractPort.DocumentText text = adapter.textOf(DOC_ID).orElseThrow();

    assertThat(text.markdown()).isEmpty();
    assertThat(text.status()).isEqualTo("PENDING_PARSE");
  }

  @Test
  @DisplayName("extract：成功与失败两种结果都要原样翻成端口的 Result")
  void extractMapsParserResult() {
    when(parser.parse(new byte[] {1, 2}, "a.docx")).thenReturn(ParseResult.ok("# 标题"));
    when(parser.parse(new byte[] {3}, "b.bin")).thenReturn(ParseResult.failed("不支持的格式", "半截正文"));

    assertThat(adapter.extract(new byte[] {1, 2}, "a.docx").ok()).isTrue();
    assertThat(adapter.extract(new byte[] {1, 2}, "a.docx").markdown()).isEqualTo("# 标题");

    DocumentExtractPort.Result failed = adapter.extract(new byte[] {3}, "b.bin");
    assertThat(failed.ok()).isFalse();
    assertThat(failed.failReason()).isEqualTo("不支持的格式");
    assertThat(failed.markdown()).isEqualTo("半截正文");
  }

  private static Document document(String name, String parsedContent, DocumentStatus status) {
    Document document = mock(Document.class);
    when(document.name()).thenReturn(name);
    when(document.parsedContent()).thenReturn(parsedContent);
    when(document.status()).thenReturn(status);
    when(document.deletedAt()).thenReturn(null);
    return document;
  }
}

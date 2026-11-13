package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.Document;
import cn.lincoln.aiplatform.knowledge.domain.DocumentRepository;
import cn.lincoln.aiplatform.knowledge.domain.ParseResult;
import cn.lincoln.aiplatform.platform.knowledge.DocumentExtractPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link DocumentExtractPort} 的真实现：解析交给 {@link TikaMarkdownParser}，取正文走 {@link DocumentRepository}。
 *
 * <p>★ 为什么需要它（而不是让工作流直接调解析器）：{@code DOCUMENT_EXTRACT_NODE} 在 {@code assistant} 模块，{@code
 * ModuleBoundaryTest} 不允许它依赖 {@code knowledge} 的实现类。端口在 {@code platform}，实现在这里 —— 与 {@code #293} 搬
 * {@code EntityRecallPort} 同一条判据。
 *
 * <p>★ 这个类<b>不做权限判断</b>：{@code docId} 指向哪份文档、调用方有没有资格看，是调用方（工作流执行器 / 未来接进来的
 * 可见性判据）的事。这里只回答"库里这份文档的正文是什么"。今天工作流的 {@code userId} 恒为 null（见 {@code WorkflowNodeContext}
 * 的注释），在这种前提下自己发明一套权限判断只会造出一个假的安全感。
 */
@Component
public class TikaDocumentExtractAdapter implements DocumentExtractPort {

  private final TikaMarkdownParser parser;
  private final DocumentRepository documents;

  public TikaDocumentExtractAdapter(TikaMarkdownParser parser, DocumentRepository documents) {
    this.parser = parser;
    this.documents = documents;
  }

  @Override
  public Result extract(byte[] content, String filename) {
    ParseResult parsed = parser.parse(content, filename);
    return parsed.success()
        ? Result.ok(parsed.markdown())
        : Result.failed(parsed.failReason(), parsed.markdown());
  }

  @Override
  public Optional<DocumentText> textOf(UUID docId) {
    if (docId == null) {
      return Optional.empty();
    }
    return documents
        .findById(docId)
        // 软删的文档按"不存在"处理：工作流不该读一份用户已经删掉的文档，而调用方分不出
        // "从没存在过"与"删掉了"，也不需要分 —— 两者对节点的处置都是同一句话。
        .filter(document -> document.deletedAt() == null)
        .map(TikaDocumentExtractAdapter::toText);
  }

  private static DocumentText toText(Document document) {
    return new DocumentText(
        document.name(),
        document.parsedContent() == null ? "" : document.parsedContent(),
        document.status() == null ? "UNKNOWN" : document.status().name());
  }
}

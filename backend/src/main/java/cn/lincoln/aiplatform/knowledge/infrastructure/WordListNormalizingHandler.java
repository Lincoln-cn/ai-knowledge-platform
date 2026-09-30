package cn.lincoln.aiplatform.knowledge.infrastructure;

import org.apache.tika.sax.ContentHandlerDecorator;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Word 列表归一器（第 6 周 · 0.2.1）—— 把 OOXML 的 {@code <p class="list_*">} 变成 {@code ul/ol/li}。
 *
 * <p>★ 为什么需要它：Tika 的 Microsoft 模块<strong>不产生 {@code ul/ol/li}</strong>（{@code 12 §2.2} 已知缺口），
 * 只给段落打上 {@code class="list_Bullet"} / {@code class="list_Number"}。 而 {@code
 * ToMarkdownContentHandler} 认的是 {@code ul/ol/li} 元素 —— 于是列表在 Markdown 里退化成普通段落， 分块器拿不到列表信号（{@code
 * 12 §2.1}）。
 *
 * <p>★ 做法：在「Tika XHTML 事件」和「Markdown 序列化器」之间插一层 SAX 装饰器， 把连续的 list 段落包成 {@code
 * ul/ol/li}。<strong>不重写 OOXML 解析</strong>，也不走 POI 二次解析 —— 缺口补在序列化边界， 代价最小（{@code 12 §2.5}
 * 的退路留给实测证明必要时）。
 */
class WordListNormalizingHandler extends ContentHandlerDecorator {

  private boolean inBulletList;
  private boolean inNumberList;

  /** 当前 {@code p} 是否被我们改写成了 {@code li}（改写过则 {@code endElement(p)} 要收成 {@code li}）。 */
  private boolean listParagraph;

  WordListNormalizingHandler(org.xml.sax.ContentHandler delegate) {
    super(delegate);
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes atts)
      throws SAXException {
    if ("p".equals(localName)) {
      String cls = atts.getValue("class");
      if (cls != null && cls.contains("list_Bullet")) {
        openList(true);
        listParagraph = true;
        super.startElement(uri, "li", "li", new AttributesImpl());
        return;
      }
      if (cls != null && cls.contains("list_Number")) {
        openList(false);
        listParagraph = true;
        super.startElement(uri, "li", "li", new AttributesImpl());
        return;
      }
    }
    // 块级元素开新段 ⇒ 先收掉列表，避免把标题/表格吞进 li
    if (isBlockBoundary(localName)) {
      closeList();
    }
    super.startElement(uri, localName, qName, atts);
  }

  @Override
  public void endElement(String uri, String localName, String qName) throws SAXException {
    if (listParagraph && "p".equals(localName)) {
      listParagraph = false;
      super.endElement(uri, "li", "li");
      return;
    }
    super.endElement(uri, localName, qName);
  }

  @Override
  public void endDocument() throws SAXException {
    closeList();
    super.endDocument();
  }

  // ────────────────────────── 内部 ──────────────────────────

  private void openList(boolean bullet) throws SAXException {
    if (bullet && inBulletList) {
      return;
    }
    if (!bullet && inNumberList) {
      return;
    }
    closeList();
    super.startElement("", bullet ? "ul" : "ol", bullet ? "ul" : "ol", new AttributesImpl());
    inBulletList = bullet;
    inNumberList = !bullet;
  }

  private void closeList() throws SAXException {
    if (inBulletList) {
      super.endElement("", "ul", "ul");
      inBulletList = false;
    }
    if (inNumberList) {
      super.endElement("", "ol", "ol");
      inNumberList = false;
    }
    listParagraph = false;
  }

  /** 会打断列表的块级标签（其余的行内格式如 span/b/i 不打断）。 */
  private static boolean isBlockBoundary(String localName) {
    return switch (localName) {
      case "h1", "h2", "h3", "h4", "h5", "h6", "div", "table", "ul", "ol", "li", "body", "html" ->
          true;
      case "p" -> true; // 非 list 的 p 在上面已 return；到这里的是普通段落
      default -> false;
    };
  }
}

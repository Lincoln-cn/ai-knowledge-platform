package cn.lincoln.aiplatform.conversation.application;

/**
 * 一轮问答的生成模式（契约 {@code ChatMode = 'NEW' | 'REGENERATE' | 'CONTINUE'}）。
 *
 * <h3>本批支持两个，第三个<strong>显式拒绝</strong></h3>
 *
 * <table border="1">
 *   <caption>模式与落地状态</caption>
 *   <tr><th>取值</th><th>语义（契约逐字）</th><th>本批</th></tr>
 *   <tr><td>{@code NEW}</td><td>新增一问一答</td><td>✅</td></tr>
 *   <tr><td>{@code REGENERATE}</td><td>覆盖 {@code targetMessageId} 对应的原答案（<b>不新增消息对</b>）</td><td>✅</td></tr>
 *   <tr>
 *     <td>{@code CONTINUE}</td><td>接着 {@code targetMessageId} 已生成的内容<b>续写</b></td>
 *     <td>❌ ★ <b>显式 400</b>，不静默当 {@code NEW}</td>
 *   </tr>
 * </table>
 *
 * <p>★★ <b>为什么 {@code CONTINUE} 必须显式拒绝、不能"先当 NEW 处理"</b>：
 * 那会<b>新增一条消息</b>，而契约给它的语义是"接着已有内容续写"（不新增问句）。 静默降级成 NEW 的后果是：用户点"继续生成"，界面上多出一问一答，而<b>看起来一切正常</b> ——
 * 这正是本仓头号病（{@code silent-failure}）的形状。 ★ 拒绝时给的是"这一格还没做"，不是"参数不合法"：请求完全合法，只是能力还没到。
 */
public enum ChatMode {
  NEW,
  REGENERATE,
  CONTINUE;

  /**
   * 解析契约取值。{@code null}/空白 ⇒ {@link #NEW}（契约写着「生成模式；不传按 new 处理」）。
   *
   * @param raw 契约里的原始取值
   * @throws IllegalArgumentException 不在三个取值里
   */
  public static ChatMode parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return NEW;
    }
    String trimmed = raw.trim().toUpperCase(java.util.Locale.ROOT);
    for (ChatMode value : values()) {
      if (value.name().equals(trimmed)) {
        return value;
      }
    }
    throw new IllegalArgumentException("mode 取值不合法：" + raw + "（合法值：NEW / REGENERATE / CONTINUE）");
  }
}

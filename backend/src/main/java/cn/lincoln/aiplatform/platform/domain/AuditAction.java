package cn.lincoln.aiplatform.platform.domain;

/**
 * 审计动作的闭集（`#257`，设计见 {@code 13 §七}）。
 *
 * <p>★ 为什么要一个枚举而不是自由字符串：审计的用途是「事后按动作类型捞记录」， 一旦写成 {@code action = '改了密级'}/{@code
 * 'reclassify'}/{@code 'DOCUMENT_RECLASSIFY'} 三种混着， 按动作检索就永远漏。闭集让 {@code where action = ?} 成为可靠查询。
 *
 * <p>★ 值一旦发布就不要再改：它是**已经写进库里的历史**，改名等于把旧记录变成孤儿。 要加新动作就加新常量。
 */
public enum AuditAction {

  /** 删除文档（软删也算：它让内容从用户视野里消失）。 */
  DOCUMENT_DELETE("document.delete"),

  /** 改文档密级（升或降，`detail` 里带 from/to）。 */
  DOCUMENT_RECLASSIFY("document.reclassify"),

  /** 改文档标签（`detail` 里带 from/to 两份标签数组）。★ 与密级同级的安全变更：标签直接影响谁能检索到它。 */
  DOCUMENT_RELABEL("document.relabel"),

  /** 导出数据（本批尚未接线，先占位以免将来名字被写歪）。 */
  DATA_EXPORT("data.export"),

  /** 授予/收回权限（本批尚未接线）。 */
  GRANT_CHANGE("grant.change"),

  /**
   * 按留存期清理审计记录（`#75`）。
   *
   * <p>★ 它是"对审计本身的变更"，所以必须自己留一条审计：一份只删不留痕的清理通道， 会把审计从"可追溯"变成"可选择性追溯"。
   */
  AUDIT_PURGE("audit.purge"),

  /**
   * 生成会话分享链接（`#258`：分享是最该留痕的一类动作 —— 它把一份内部内容变成"有链接就能看"）。
   *
   * <p>★ 明细里**只记令牌前 8 位**，不记完整令牌：令牌本身就是访问凭证，把它抄进审计表等于 让"能读审计的人"顺手拿到一批访问入口。
   */
  CONVERSATION_SHARE("conversation.share"),

  /** 撤销会话分享链接（与生成成对：一条链接的生命周期两端都要能回答"谁做的"）。 */
  CONVERSATION_UNSHARE("conversation.unshare");

  private final String code;

  AuditAction(String code) {
    this.code = code;
  }

  /** 落库用的稳定编码（点分小写）。 */
  public String code() {
    return code;
  }
}

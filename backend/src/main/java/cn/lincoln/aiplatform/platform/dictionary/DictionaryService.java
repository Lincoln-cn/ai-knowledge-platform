package cn.lincoln.aiplatform.platform.dictionary;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基础数据字典的读写（{@code 08 §十}）—— 密级 / 文种 / 提示词类型 / 库分类的<strong>真源</strong>。
 *
 * <h3>★ 两条规则是这个类存在的理由</h3>
 *
 * <p><strong>一、字典项不允许删除，只能停用。</strong>{@code code} 一旦写进历史数据 （{@code kb_document.doc_type}、{@code
 * kb_knowledge_base.category}…），删掉它会让那些数据的展示
 * <em>回落成机器码</em>——而**没有任何地方报错**。停用表达的是"新数据不要再选它"，既有数据照旧可读：
 * 这才是我们要的语义，而且它不需要去问"有没有人还在用这个值"（那种跨模块的引用检查 在 {@code platform} 这一层根本做不了——{@code knowledge}
 * 的表不是它的依赖）。
 *
 * <p><strong>二、密级（{@link DictionaryKind#CLASSIFICATION}）禁止增删。</strong>它不是一张清单，而是 一个**排序比较轴**：{@code
 * classification_rank} 是生成列，{@code CASE} 写死了四档（{@code V21}/{@code V22}）， {@code
 * ClassificationRankTest} 断言"库里的 rank == Java 的 ordinal"。往字典里插第五档的症状是
 * "新档位的文档对<strong>所有人</strong>都不可见"（{@code else 99} = fail-closed），而无人知道为什么。 要加档就得连生成列一起迁移 ——
 * 所以这里<strong>明确拒绝</strong>，并把那句话写进错误文案 （报错要指路，{@code 08 §4.1}）。
 *
 * <h3>为什么实现直接在 {@code platform} 里，不再分端口与实现</h3>
 *
 * <p>本项目的约定是"跨模块 SPI 放 platform、实现放提供方模块"。但字典的**消费者是前端** （建档/上传的下拉走 HTTP 取），今天没有任何 Java 模块要从这儿读值。 ⇒
 * 按"别为假想的调用方造抽象"（本项目在别处反复吃过这个亏），先只做一个服务； 真出现 Java 消费方时再抽接口，那时它是**有真实调用方**的抽象。
 */
@Service
public class DictionaryService {

  /** {@code code} 的形状：字母/数字/下划线，中文取值（文种）也走这一支 —— 见迁移文件头的说明。 */
  private static final Pattern CODE = Pattern.compile("[\\p{IsHan}\\p{Alnum}_]{1,64}");

  private static final int MAX_LABEL = 64;

  private static final int MAX_REMARK = 255;

  private final JdbcTemplate jdbc;

  public DictionaryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 某一类字典项。
   *
   * @param includeDisabled 维护页要看到停用项（否则"停用了什么"没人查得到）；消费方的下拉只要启用的
   */
  @Transactional(readOnly = true)
  public List<DictionaryItem> items(DictionaryKind kind, boolean includeDisabled) {
    String sql =
        "select kind, code, label, sort, enabled, remark from platform_dict_item where kind = ?"
            + (includeDisabled ? "" : " and enabled")
            + " order by sort, code";
    return jdbc.query(
        sql,
        (rs, i) ->
            new DictionaryItem(
                DictionaryKind.valueOf(rs.getString("kind")),
                rs.getString("code"),
                rs.getString("label"),
                rs.getInt("sort"),
                rs.getBoolean("enabled"),
                rs.getString("remark")),
        kind.name());
  }

  /** 新增一项。★ 密级拒绝新增（见类注释）。 */
  @Transactional
  public DictionaryItem create(
      DictionaryKind kind, String code, String label, Integer sort, String remark, UUID actorId) {
    String trimmedCode = require(kind, code);
    if (kind == DictionaryKind.CLASSIFICATION) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "密级不允许新增档位",
          "密级是排序比较轴：classification_rank 是生成列，四档写死在 V21/V22 里。"
              + "加一档要同时改生成列（一次迁移）并跑 ClassificationRankTest，否则新档位的内容对所有人都不可见");
    }
    if (exists(kind, trimmedCode)) {
      // ★ 这里没有独立的 409 码（ErrorCode 是一份固定清单，本项目不随意加码）——
      //   冲突对本项目而言就是"这次入参不合法"的一种，用 BAD_REQUEST + 可读原因表达
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "「" + trimmedCode + "」已经存在",
          "同一种类内的 code 必须唯一 —— 要改展示名请编辑那一项，不要新增");
    }
    Integer nextSort = sort == null ? nextSort(kind) : sort;
    String trimmedLabel = requireLabel(label);
    String trimmedRemark = trim(remark, MAX_REMARK, "备注");
    jdbc.update(
        "insert into platform_dict_item (id, kind, code, label, sort, enabled, remark, created_at,"
            + " created_by, updated_at, updated_by) values (?, ?, ?, ?, ?, true, ?, now(), ?,"
            + " now(), ?)",
        UUID.randomUUID(),
        kind.name(),
        trimmedCode,
        trimmedLabel,
        nextSort,
        trimmedRemark,
        actorId,
        actorId);
    return new DictionaryItem(kind, trimmedCode, trimmedLabel, nextSort, true, trimmedRemark);
  }

  /**
   * 改一项（按业务键 {@code (kind, code)} 定位）。
   *
   * <p>★ {@code code} **不可改**：它已经写进历史数据与召回前缀。要"换名字"就改 {@code label} —— 那正是把文案与机器码分开的意义（改 label
   * 不触发重新向量化）。
   */
  @Transactional
  public DictionaryItem update(
      DictionaryKind kind,
      String code,
      String label,
      Integer sort,
      Boolean enabled,
      String remark,
      UUID actorId) {
    String trimmedCode = require(kind, code);
    if (!exists(kind, trimmedCode)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "字典项不存在：「" + trimmedCode + "」", "请刷新后重试");
    }
    jdbc.update(
        "update platform_dict_item set"
            + " label = coalesce(?, label),"
            + " sort = coalesce(?, sort),"
            + " enabled = coalesce(?, enabled),"
            + " remark = coalesce(?, remark),"
            + " updated_at = now(), updated_by = ?"
            + " where kind = ? and code = ?",
        label == null ? null : requireLabel(label),
        sort,
        enabled,
        remark == null ? null : trim(remark, MAX_REMARK, "备注"),
        actorId,
        kind.name(),
        trimmedCode);
    return items(kind, true).stream()
        .filter(item -> item.code().equals(trimmedCode))
        .findFirst()
        .orElseThrow();
  }

  // ────────────────────────── 内部 ──────────────────────────

  private boolean exists(DictionaryKind kind, String code) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from platform_dict_item where kind = ? and code = ?",
            Integer.class,
            kind.name(),
            code);
    return count != null && count > 0;
  }

  private int nextSort(DictionaryKind kind) {
    Integer max =
        jdbc.queryForObject(
            "select coalesce(max(sort), 0) from platform_dict_item where kind = ?",
            Integer.class,
            kind.name());
    return (max == null ? 0 : max) + 1;
  }

  private static String require(DictionaryKind kind, String code) {
    if (code == null || code.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少 code", "code 是业务键，不能为空");
    }
    String trimmed = code.trim();
    if (!CODE.matcher(trimmed).matches()) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "code 只允许中文、字母、数字与下划线，且不超过 64 字：收到「" + trimmed + "」",
          "code 会写进历史数据与召回前缀，形状必须稳定");
    }
    return trimmed;
  }

  private static String requireLabel(String label) {
    if (label == null || label.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少展示名", "展示名会出现在下拉里，不能为空");
    }
    String trimmed = label.trim();
    if (trimmed.length() > MAX_LABEL) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "展示名不能超过 " + MAX_LABEL + " 字");
    }
    return trimmed;
  }

  private static String trim(String value, int max, String what) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > max) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, what + "不能超过 " + max + " 字");
    }
    return trimmed;
  }
}

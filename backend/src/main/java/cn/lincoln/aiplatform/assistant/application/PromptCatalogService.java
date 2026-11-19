package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.PromptConfig;
import cn.lincoln.aiplatform.assistant.domain.PromptConfigVersion;
import cn.lincoln.aiplatform.assistant.domain.PromptKind;
import cn.lincoln.aiplatform.assistant.domain.PromptType;
import cn.lincoln.aiplatform.assistant.infrastructure.JpaPromptConfigRepository;
import cn.lincoln.aiplatform.assistant.infrastructure.JpaPromptConfigVersionRepository;
import cn.lincoln.aiplatform.assistant.infrastructure.JpaPromptTypeRepository;
import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提示词类型与模板的管理服务（#196）。
 *
 * <p>两张表都只是“管理端资产”：应用侧仍只读 {@code as_assistant_prompt.prompt_config_id}，本服务不参与问答链路。
 * 类型/模板的删除都做引用检查——删除被引用的类型或模板会把应用指向空，属于“静默破坏”，必须 400 拒绝。
 */
@Service
public class PromptCatalogService {

  private static final int MAX_PAGE_SIZE = 100;

  private final JpaPromptTypeRepository types;
  private final JpaPromptConfigRepository configs;
  private final JpaPromptConfigVersionRepository versions;
  private final JdbcTemplate jdbc;

  public PromptCatalogService(
      JpaPromptTypeRepository types,
      JpaPromptConfigRepository configs,
      JpaPromptConfigVersionRepository versions,
      JdbcTemplate jdbc) {
    this.types = types;
    this.configs = configs;
    this.versions = versions;
    this.jdbc = jdbc;
  }

  /** 类型列表（分页）。 */
  @Transactional(readOnly = true)
  public PageResult<PromptTypeView> listTypes(Integer page, Integer size) {
    int safePage = page == null || page < 1 ? 1 : page;
    int safeSize = size == null || size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    List<PromptTypeView> all =
        types.findAllByOrderByCreatedAtAsc().stream()
            .map(
                t ->
                    new PromptTypeView(
                        t.id(), t.typeName(), t.icon(), configs.countByTypeId(t.id())))
            .toList();
    int from = Math.min((safePage - 1) * safeSize, all.size());
    int to = Math.min(from + safeSize, all.size());
    return PageResult.of(all.subList(from, to), all.size(), safePage, safeSize);
  }

  @Transactional
  public PromptTypeView createType(String typeName, String icon) {
    String name = normalizeName(typeName);
    if (types.existsByTypeNameIgnoreCase(name)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词类型已存在", "换一个类型名称");
    }
    PromptType type = new PromptType(UUID.randomUUID(), name, icon, Instant.now());
    try {
      types.save(type);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage(), "检查类型名称与图标");
    }
    return new PromptTypeView(type.id(), type.typeName(), type.icon(), 0);
  }

  @Transactional
  public PromptTypeView updateType(UUID id, String typeName, String icon) {
    PromptType type =
        types
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提示词类型不存在"));
    if (typeName != null && !typeName.isBlank()) {
      String name = normalizeName(typeName);
      if (types.existsByTypeNameIgnoreCaseAndIdNot(name, id)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词类型已存在", "换一个类型名称");
      }
      typeName = name;
    }
    try {
      type.rename(
          typeName == null ? type.typeName() : typeName,
          icon == null ? type.icon() : icon,
          Instant.now());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage(), "检查类型名称与图标");
    }
    types.save(type);
    return new PromptTypeView(type.id(), type.typeName(), type.icon(), configs.countByTypeId(id));
  }

  @Transactional
  public void deleteType(UUID id) {
    if (!types.existsById(id)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "提示词类型不存在");
    }
    long count = configs.countByTypeId(id);
    if (count > 0) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "该类型下还有 " + count + " 个提示词", "先删除或移动这些提示词，再删类型");
    }
    types.deleteById(id);
  }

  /** 模板列表（分页 + 类型筛选 + 关键字 + 用途 + 推荐应用）。 */
  @Transactional(readOnly = true)
  public PageResult<PromptConfigView> listConfigs(
      Integer page, Integer size, UUID typeId, String keyword, PromptKind kind, UUID appId) {
    int safePage = page == null || page < 1 ? 1 : page;
    int safeSize = size == null || size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    Map<UUID, PromptType> typeMap =
        types.findAll().stream().collect(Collectors.toMap(PromptType::id, Function.identity()));
    String kw = keyword == null ? null : keyword.strip().toLowerCase(Locale.ROOT);
    List<PromptConfigView> all =
        configs.findAllByOrderByCreatedAtDesc().stream()
            // ★ kind 为 null 表示不过滤（管理页的「全部」）。调用方要"只看角色指令"必须显式传 ROLE ——
            //   从前这里的含义就是角色指令，给一个默认值会让"提问模板悄悄混进应用配置的选用列表"。
            .filter(c -> kind == null || kind == c.kind())
            .filter(c -> appId == null || appId.equals(c.appId()))
            .filter(c -> typeId == null || typeId.equals(c.typeId()))
            .filter(
                c ->
                    kw == null
                        || kw.isEmpty()
                        || c.roleName().toLowerCase(Locale.ROOT).contains(kw)
                        || c.callWord().toLowerCase(Locale.ROOT).contains(kw)
                        || c.description().toLowerCase(Locale.ROOT).contains(kw))
            .map(
                c -> {
                  PromptType t = typeMap.get(c.typeId());
                  return new PromptConfigView(
                      c.id(),
                      c.kind(),
                      c.appId(),
                      c.roleName(),
                      c.callWord(),
                      c.typeId(),
                      t == null ? "未分类" : t.typeName(),
                      c.description(),
                      c.icon(),
                      usageCount(c.id()),
                      c.createdAt());
                })
            .toList();
    int from = Math.min((safePage - 1) * safeSize, all.size());
    int to = Math.min(from + safeSize, all.size());
    return PageResult.of(all.subList(from, to), all.size(), safePage, safeSize);
  }

  @Transactional
  public PromptConfigView createConfig(
      PromptKind kind,
      UUID appId,
      String roleName,
      String callWord,
      UUID typeId,
      String description,
      String icon) {
    requireType(typeId);
    PromptConfig config =
        new PromptConfig(
            UUID.randomUUID(),
            kind,
            appId,
            roleName,
            callWord,
            typeId,
            description,
            icon,
            Instant.now());
    try {
      configs.save(config);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage(), "检查名称、正文与图标");
    }
    PromptType t = types.findById(typeId).orElse(null);
    return new PromptConfigView(
        config.id(),
        config.kind(),
        config.appId(),
        config.roleName(),
        config.callWord(),
        config.typeId(),
        t == null ? "未分类" : t.typeName(),
        config.description(),
        config.icon(),
        0,
        config.createdAt());
  }

  /**
   * 改一条提示词；内容真的变了才把**旧值**追加成一版历史（ROADMAP §11.62）。
   *
   * <p>★ 三个判据：(a) 比较的是"这次要写进去的值"与"现在库里的值"，不是"请求体有没有这些字段"—— 传 null 表示沿用旧值，不能因此判成"变了"；(b)
   * 全等就**不写版本**：点开编辑器又保存一次不该多一版噪声；(c) 落的是**改之前**的快照 —— 历史表回答的是"以前长什么样"，最新一版始终在 {@code
   * as_prompt_config} 里。
   */
  private void snapIfChanged(
      PromptConfig config,
      PromptKind kind,
      UUID appId,
      String roleName,
      String callWord,
      UUID typeId,
      String description,
      String icon,
      UUID actorId) {
    PromptKind nextKind = kind == null ? config.kind() : kind;
    UUID nextAppId = nextKind == PromptKind.QUESTION ? appId : null;
    String nextRoleName = roleName == null ? config.roleName() : roleName;
    String nextCallWord = callWord == null ? config.callWord() : callWord;
    UUID nextTypeId = typeId == null ? config.typeId() : typeId;
    String nextDescription = description == null ? config.description() : description;
    String nextIcon = icon == null ? config.icon() : icon;
    boolean unchanged =
        nextKind == config.kind()
            && java.util.Objects.equals(nextAppId, config.appId())
            && nextRoleName.equals(config.roleName())
            && nextCallWord.equals(config.callWord())
            && nextTypeId.equals(config.typeId())
            && nextDescription.equals(config.description())
            && nextIcon.equals(config.icon());
    if (unchanged) {
      return;
    }
    int nextVersionNo = versions.maxVersionNo(config.id()) + 1;
    versions.save(
        new PromptConfigVersion(
            UUID.randomUUID(), config.id(), nextVersionNo, config, Instant.now(), actorId));
  }

  /** 某条提示词的历史版本（新的在前）；只给摘要，正文按需取。 */
  @Transactional(readOnly = true)
  public List<PromptConfigVersionSummary> listVersions(UUID configId) {
    requireConfig(configId);
    return versions.findByConfigIdOrderByVersionNoDesc(configId).stream()
        .map(
            v ->
                new PromptConfigVersionSummary(
                    v.versionNo(), v.savedAt(), v.savedBy(), previewOf(v.callWord())))
        .toList();
  }

  /** 某一版的全文（用于前端做逐行 diff）。 */
  @Transactional(readOnly = true)
  public PromptConfigVersionDetail getVersion(UUID configId, int versionNo) {
    requireConfig(configId);
    PromptConfigVersion v =
        versions
            .findByConfigIdAndVersionNo(configId, versionNo)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "这个版本不存在（可能已被清理）"));
    return new PromptConfigVersionDetail(
        v.versionNo(),
        v.roleName(),
        v.callWord(),
        v.typeId(),
        v.description(),
        v.icon(),
        v.savedAt(),
        v.savedBy());
  }

  /**
   * 把某一版内容写回成**新的一版**（历史不删）。
   *
   * <p>★ 不做"删掉 v4 之后的历史"：回滚本身也是一次修改，它应该留下痕迹 —— 否则"回滚过"这件事在记录里消失， 事后没人知道是谁把版本倒回去过。
   */
  @Transactional
  public PromptConfigView restoreVersion(UUID configId, int versionNo, UUID actorId) {
    PromptConfigVersion v =
        versions
            .findByConfigIdAndVersionNo(configId, versionNo)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "这个版本不存在（可能已被清理）"));
    return updateConfig(
        configId,
        v.kind(),
        v.appId(),
        v.roleName(),
        v.callWord(),
        v.typeId(),
        v.description(),
        v.icon(),
        actorId);
  }

  /**
   * 按 id 取配置（只读）。
   *
   * <p>★ 给评测用：它要拿"当前已保存的正文"与草稿比，也需要判断这条提示词还在不在。 不暴露实体给控制器 —— 这是应用层之间的读口。
   */
  public java.util.Optional<PromptConfig> findConfig(UUID id) {
    return configs.findById(id);
  }

  private PromptConfig requireConfig(UUID configId) {
    return configs
        .findById(configId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提示词不存在"));
  }

  private static String previewOf(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= 60 ? text : text.substring(0, 60) + "…";
  }

  @Transactional
  public PromptConfigView updateConfig(
      UUID id,
      PromptKind kind,
      UUID appId,
      String roleName,
      String callWord,
      UUID typeId,
      String description,
      String icon,
      UUID actorId) {
    PromptConfig config =
        configs
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提示词不存在"));
    if (typeId != null) {
      requireType(typeId);
    }
    snapIfChanged(config, kind, appId, roleName, callWord, typeId, description, icon, actorId);
    try {
      config.update(
          kind == null ? config.kind() : kind,
          appId,
          roleName == null ? config.roleName() : roleName,
          callWord == null ? config.callWord() : callWord,
          typeId == null ? config.typeId() : typeId,
          description == null ? config.description() : description,
          icon == null ? config.icon() : icon,
          Instant.now());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage(), "检查名称、正文与图标");
    }
    configs.save(config);
    PromptType t = types.findById(config.typeId()).orElse(null);
    return new PromptConfigView(
        config.id(),
        config.kind(),
        config.appId(),
        config.roleName(),
        config.callWord(),
        config.typeId(),
        t == null ? "未分类" : t.typeName(),
        config.description(),
        config.icon(),
        usageCount(config.id()),
        config.createdAt());
  }

  @Transactional
  public void deleteConfig(UUID id) {
    if (!configs.existsById(id)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "提示词不存在");
    }
    long used = usageCount(id);
    if (used > 0) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST, "该提示词已被 " + used + " 个应用引用", "先解绑这些应用，再删除提示词");
    }
    configs.deleteById(id);
  }

  private void requireType(UUID typeId) {
    if (typeId == null || !types.existsById(typeId)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "提示词类型不存在", "先在类型列表里创建它");
    }
  }

  /**
   * 引用数 = <strong>还活着的</strong>应用数。
   *
   * <p>★ {@code #217}：删应用是软删（{@code Assistant#markDeleted} 只写 {@code deleted_at}），绑定表 {@code
   * as_assistant_prompt} 里那几行不会跟着消失。早期口径只 count 绑定表、不看 {@code
   * as_assistant.deleted_at}，后果是任何被应用用过的提示词<strong>永久删不掉</strong>（400「已被 1 个应用引用」），而那个应用早就从界面上消失了
   * —— 用户没有任何办法解开这个环，连它的类型也被「该类型下还有 N 个提示词」一起锁死。
   *
   * <p>口径选择：按「活着的引用」算，而不是在删应用时级联删绑定行。级联删会把「这个提示词曾被谁用过」这段历史一起丢掉（排查与审计要用），而「能不能删」只该看「现在还有没有人在用」。
   */
  private long usageCount(UUID configId) {
    Long count =
        jdbc.queryForObject(
            "select count(distinct p.assistant_id) from as_assistant_prompt p"
                + " join as_assistant a on a.id = p.assistant_id"
                + " where p.prompt_config_id = ? and a.deleted_at is null",
            Long.class,
            configId);
    return count == null ? 0 : count;
  }

  private static String normalizeName(String typeName) {
    if (typeName == null || typeName.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "类型名称不能为空");
    }
    return typeName.strip();
  }

  /** 类型视图。 */
  public record PromptTypeView(UUID id, String typeName, String icon, long promptCount) {}

  /** 版本摘要（列表用）。 */
  public record PromptConfigVersionSummary(
      int versionNo, Instant savedAt, UUID savedBy, String preview) {}

  /** 某一版的全文。 */
  public record PromptConfigVersionDetail(
      int versionNo,
      String roleName,
      String callWord,
      UUID typeId,
      String description,
      String icon,
      Instant savedAt,
      UUID savedBy) {}

  /** 模板视图。 */
  public record PromptConfigView(
      UUID id,
      PromptKind kind,
      UUID appId,
      String roleName,
      String callWord,
      UUID typeId,
      String typeName,
      String description,
      String icon,
      long usageCount,
      Instant createdAt) {}
}

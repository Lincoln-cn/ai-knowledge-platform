package cn.lincoln.aiplatform.assistant.application;

import cn.lincoln.aiplatform.assistant.domain.PromptConfig;
import cn.lincoln.aiplatform.assistant.domain.PromptType;
import cn.lincoln.aiplatform.assistant.infrastructure.JpaPromptConfigRepository;
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
  private final JdbcTemplate jdbc;

  public PromptCatalogService(
      JpaPromptTypeRepository types, JpaPromptConfigRepository configs, JdbcTemplate jdbc) {
    this.types = types;
    this.configs = configs;
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

  /** 模板列表（分页 + 类型筛选 + 关键字）。 */
  @Transactional(readOnly = true)
  public PageResult<PromptConfigView> listConfigs(
      Integer page, Integer size, UUID typeId, String keyword) {
    int safePage = page == null || page < 1 ? 1 : page;
    int safeSize = size == null || size < 1 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    Map<UUID, PromptType> typeMap =
        types.findAll().stream().collect(Collectors.toMap(PromptType::id, Function.identity()));
    String kw = keyword == null ? null : keyword.strip().toLowerCase(Locale.ROOT);
    List<PromptConfigView> all =
        configs.findAllByOrderByCreatedAtDesc().stream()
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
      String roleName, String callWord, UUID typeId, String description, String icon) {
    requireType(typeId);
    PromptConfig config =
        new PromptConfig(
            UUID.randomUUID(), roleName, callWord, typeId, description, icon, Instant.now());
    try {
      configs.save(config);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage(), "检查角色名称、提示词与图标");
    }
    PromptType t = types.findById(typeId).orElse(null);
    return new PromptConfigView(
        config.id(),
        config.roleName(),
        config.callWord(),
        config.typeId(),
        t == null ? "未分类" : t.typeName(),
        config.description(),
        config.icon(),
        0,
        config.createdAt());
  }

  @Transactional
  public PromptConfigView updateConfig(
      UUID id, String roleName, String callWord, UUID typeId, String description, String icon) {
    PromptConfig config =
        configs
            .findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "提示词不存在"));
    if (typeId != null) {
      requireType(typeId);
    }
    try {
      config.update(
          roleName == null ? config.roleName() : roleName,
          callWord == null ? config.callWord() : callWord,
          typeId == null ? config.typeId() : typeId,
          description == null ? config.description() : description,
          icon == null ? config.icon() : icon,
          Instant.now());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage(), "检查角色名称、提示词与图标");
    }
    configs.save(config);
    PromptType t = types.findById(config.typeId()).orElse(null);
    return new PromptConfigView(
        config.id(),
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

  private long usageCount(UUID configId) {
    Long count =
        jdbc.queryForObject(
            "select count(distinct assistant_id) from as_assistant_prompt where prompt_config_id ="
                + " ?",
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

  /** 模板视图。 */
  public record PromptConfigView(
      UUID id,
      String roleName,
      String callWord,
      UUID typeId,
      String typeName,
      String description,
      String icon,
      long usageCount,
      Instant createdAt) {}
}

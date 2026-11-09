package cn.lincoln.aiplatform.assistant.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 应用模板（{@code #295} 的后半）。
 *
 * <h3>★ 模板是「只读投影」，不是「应用的另一种形态」</h3>
 *
 * <p>应用（{@link Assistant}）会因为使用而变：发布状态、调用计数、满意度、更新时间。模板不会。
 * 模板存的是<strong>建这个应用时那一次的快照</strong>，用它再建一个新应用之后，两者各走各的 —— 所以这里存 {@link #payload()}（配置快照），而不是拿外键指向
 * {@code as_assistant} 的可变字段。
 *
 * <p>★ 由此推出一条硬约束：{@code payload} 里<strong>只放"建应用需要的那几格"</strong>， 不放 {@code callCount} / {@code
 * tokenCount} / {@code satisfaction} / {@code createdAt} 这类运行时计数。 带上它们的话，克隆出来的应用会一出生就背着一个假的"已经被调用过
 * N 次"。
 *
 * <h3>为什么 {@code sourceAppId} 不设外键</h3>
 *
 * <p>模板的用途是"这个配置值得复用"，而不是"这个应用还有副本"。应用被删了，模板应当留下 —— 那正是模板存在的意义。所以这里是普通 UUID 列，不是外键。
 *
 * @param id 模板 id
 * @param name 名称（未删范围内唯一）
 * @param description 一句话说明
 * @param category 分类（{@code GENERAL}/{@code LEGAL}/…，列表页按它分组）
 * @param sourceAppId 从哪个应用抽出来；内置模板为 {@code null}
 * @param payload 建应用所需的配置快照（只读，克隆时深拷）
 * @param builtin 内置模板：不允许删除、不允许改
 */
public record Template(
    UUID id,
    String name,
    String description,
    String category,
    UUID sourceAppId,
    Map<String, Object> payload,
    boolean builtin,
    Instant createdAt,
    UUID createdBy,
    Instant updatedAt,
    UUID updatedBy) {

  /** 名称上限（与迁移里的 {@code varchar(100)} 对齐） */
  public static final int NAME_MAX = 100;

  /** 说明上限（与迁移里的 {@code varchar(500)} 对齐） */
  public static final int DESCRIPTION_MAX = 500;

  public Template {
    Objects.requireNonNull(id, "模板 id 不能为空");
    name = requireText(name, "模板名称", NAME_MAX);
    description = description == null ? "" : description.strip();
    if (description.length() > DESCRIPTION_MAX) {
      throw new IllegalArgumentException("模板说明不能超过 " + DESCRIPTION_MAX + " 字");
    }
    category = (category == null || category.isBlank()) ? "GENERAL" : category.strip();
    // ★ 深拷一层：调用方之后改自己那份 map 不应影响模板（模板是快照，不是引用）
    payload = payload == null ? Map.of() : deepCopy(payload);
  }

  /**
   * 内置模板不允许删除 / 修改。
   *
   * <p>★ 为什么是"内置"而不是"系统模板"：这一版的内置模板就是代码里随包发布的那几个 seed， 它们的存在意义是"新装的环境打开模板中心不是一片空白"，而不是权限概念。
   */
  public void requireEditable() {
    if (builtin) {
      throw new IllegalStateException("内置模板不允许修改或删除");
    }
  }

  private static String requireText(String value, String what, int max) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + "不能为空");
    }
    String trimmed = value.strip();
    if (trimmed.length() > max) {
      throw new IllegalArgumentException(what + "不能超过 " + max + " 字");
    }
    return trimmed;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> deepCopy(Map<String, Object> src) {
    Map<String, Object> copy = new LinkedHashMap<>();
    src.forEach(
        (k, v) -> {
          if (v instanceof Map<?, ?> m) {
            copy.put(k, deepCopy((Map<String, Object>) m));
          } else if (v instanceof java.util.List<?> list) {
            copy.put(k, new java.util.ArrayList<>(list));
          } else {
            copy.put(k, v);
          }
        });
    return Map.copyOf(copy);
  }
}

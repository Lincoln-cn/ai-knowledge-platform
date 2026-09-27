package cn.lincoln.aiplatform.platform.access;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link Visibility} ↔ 库里的小写字面量。
 *
 * <p>★ 为什么不让 Hibernate 直接 {@code @Enumerated(STRING)}：那样库里会存 {@code PRIVATE} / {@code SHARED} /
 * {@code PUBLIC}（枚举名），而**契约里是小写** （{@code 'private' | 'shared' | 'public'}）。库与契约不一致的代价会在三处显形： 手写
 * SQL 的人要记得转、导数据的人要记得转、排障的人读到一个不认识的值。
 *
 * <p>映射只放在这一处（{@code autoApply = true}），于是实体上不必逐个标注 —— 少一处标注就少一个"忘了标"的机会。
 */
@Converter(autoApply = true)
public class VisibilityConverter implements AttributeConverter<Visibility, String> {

  @Override
  public String convertToDatabaseColumn(Visibility attribute) {
    return attribute == null ? null : attribute.name().toLowerCase();
  }

  @Override
  public Visibility convertToEntityAttribute(String dbData) {
    return dbData == null ? null : Visibility.valueOf(dbData.toUpperCase());
  }
}

package cn.lincoln.aiplatform.knowledge.infrastructure;

import cn.lincoln.aiplatform.knowledge.domain.ModelCapabilityPort;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 模型能力查询的 JDBC 实现（第 7 周 · 0.2.2 批 2b）。
 *
 * <p>★ 直查 {@code ai_model} 表而不是用 {@code modelcatalog} 的 {@code ModelService}： Spring Modulith
 * 的模块边界把 {@code modelcatalog.application} 视为非公开 API。 直查表是「基础设施层跨模块读」的可接受形态（不引入领域类型依赖）。
 *
 * <p>★ {@code context_window} 列当前只对对话模型有值（向量模型为 null）—— 此路径实际 只在模型元数据补全后生效。查不到时调用方回落配置。
 */
@Component
public class JdbcModelCapabilityAdapter implements ModelCapabilityPort {

  private final JdbcTemplate jdbc;

  public JdbcModelCapabilityAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Integer contextWindow(UUID modelId) {
    try {
      return jdbc.queryForObject(
          "select context_window from ai_model where id = ?", Integer.class, modelId);
    } catch (Exception e) {
      // 模型不存在 / 列为 null ⇒ 返回 null，调用方回落配置
      return null;
    }
  }

  @Override
  public Integer maxChars(UUID modelId) {
    try {
      return jdbc.queryForObject(
          "select max_chars from ai_model where id = ?", Integer.class, modelId);
    } catch (Exception e) {
      // 模型不存在 / 列为 null ⇒ null（调用方继续往下退：窗口×比例 → 配置回落值）
      return null;
    }
  }
}

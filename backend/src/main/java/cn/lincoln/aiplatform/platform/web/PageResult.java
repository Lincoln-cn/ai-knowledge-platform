package cn.lincoln.aiplatform.platform.web;

import java.util.List;

/**
 * 统一分页响应（契约 {@code PageResult}，{@code frontend/src/api/types.ts:19-24}）。
 *
 * <p>★ 放在 {@code platform}：分页是"所有列表端点共用"的通用能力（{@code 08 §4.0} 第 6 行）， 不属于任何业务域。
 *
 * <p>★ {@code total} 是<strong>过滤之后</strong>的总数，而不是表里的行数 —— 这正是 {@code 08 §4.4.3} 纪律 1
 * 要防的那件事：如果先分页查、再在内存里按范围筛， 用户会看到"共 100 条"却只翻得出 3 条，而每一页的条数还都不一样。
 *
 * @param page 从 1 开始（契约与前端一致）
 */
public record PageResult<T>(List<T> list, long total, int page, int size) {

  public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
    return new PageResult<>(list, total, page, size);
  }
}

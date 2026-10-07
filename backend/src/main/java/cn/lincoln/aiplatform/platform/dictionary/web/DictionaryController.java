package cn.lincoln.aiplatform.platform.dictionary.web;

import cn.lincoln.aiplatform.platform.dictionary.DictionaryItem;
import cn.lincoln.aiplatform.platform.dictionary.DictionaryKind;
import cn.lincoln.aiplatform.platform.dictionary.DictionaryService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基础数据字典（{@code 08 §十}）。
 *
 * <h3>★ 读与写的权限是**分开**的，这是刻意的</h3>
 *
 * <p>读：<strong>登录即可</strong>。建档/上传/筛选的下拉都要读它 —— 如果一个普通上传者读不到文种清单，
 * 他就没法选文种（而"文种"还是召回前缀的一部分）。把读也卡上管理权限，等于让所有人都去点上传对话框时撞 403。
 *
 * <p>写：{@code platform:dict:manage}。它是**基础数据**，改一次影响全平台的下拉与既有数据的展示。
 *
 * <h3>★ 为什么没有 DELETE</h3>
 *
 * <p>字典项只停用不删（见 {@link DictionaryService} 的类注释）：{@code code} 已经写进历史数据，
 * 删掉会让那些数据的展示回落成机器码而无人报错。停用就是这个接口里的 {@code enabled=false}。
 */
@RestController
@RequestMapping("/api/v1/dictionaries")
public class DictionaryController {

  private final DictionaryService dictionaries;

  public DictionaryController(DictionaryService dictionaries) {
    this.dictionaries = dictionaries;
  }

  /**
   * 取某一类字典项。
   *
   * @param includeDisabled 维护页传 {@code true}（否则看不到自己停用过什么）； 消费方的下拉不传，只拿启用的
   */
  @GetMapping
  public ApiResponse<List<DictionaryItem>> list(
      @RequestParam("kind") DictionaryKind kind,
      @RequestParam(value = "includeDisabled", required = false, defaultValue = "false")
          boolean includeDisabled) {
    return ApiResponse.ok(dictionaries.items(kind, includeDisabled));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('platform:dict:manage')")
  public ApiResponse<DictionaryItem> create(
      @RequestBody UpsertBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        dictionaries.create(
            body.kind(),
            body.code(),
            body.label(),
            body.sort(),
            body.remark(),
            UUID.fromString(jwt.getSubject())));
  }

  /**
   * 改一项（按业务键 {@code (kind, code)} 定位）。★ 契约是 Partial：只传要改的字段。
   *
   * <p>★ {@code code} 不可改（它是历史数据与召回前缀里那个值）——所以要改的就是 {@code label / sort / enabled / remark}，而且改
   * {@code label} **不会**触发重新向量化。
   */
  @PutMapping
  @PreAuthorize("hasAuthority('platform:dict:manage')")
  public ApiResponse<DictionaryItem> update(
      @RequestBody UpsertBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        dictionaries.update(
            body.kind(),
            body.code(),
            body.label(),
            body.sort(),
            body.enabled(),
            body.remark(),
            UUID.fromString(jwt.getSubject())));
  }

  /**
   * 请求体（新增与修改共用一个形状；修改时只有 {@code kind}/{@code code} 必填）。
   *
   * @param enabled 停用 = 不再出现在新的下拉；既有数据照旧可读
   */
  public record UpsertBody(
      DictionaryKind kind,
      String code,
      String label,
      Integer sort,
      Boolean enabled,
      String remark) {}
}

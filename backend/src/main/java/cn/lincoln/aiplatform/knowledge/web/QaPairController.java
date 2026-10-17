package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.QaPairService;
import cn.lincoln.aiplatform.knowledge.application.QaPairView;
import cn.lincoln.aiplatform.platform.access.ClassificationLevel;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.PageResult;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答对的 web 入口（需求 {@code 03 §六 FR-03-12}）。
 *
 * <h3>★ 为什么放在知识库侧（不是应用侧）</h3>
 *
 * <p>口径见设计册 {@code 10 §7.4.1}：问答对是<strong>知识材料</strong>（问题 ↔ 答案 ↔ 出处分段）， 与文档 /
 * 分段同级；应用消费的是<strong>检索结果</strong>，不是问答对本身。 ★ 应用配置里的 {@code
 * quickQuestions}（开场白快捷问）<strong>不是</strong>问答对。
 *
 * <h3>★★ 本期检索侧一行不接（{@code #78} 的裁定）</h3>
 *
 * <p>这些端点写进去的问答对<strong>不会</strong>被检索读到。★ 这条要写在界面上， 否则用户建完会以为"检索已经用上了"—— 那是本仓最忌讳的静默期待。
 *
 * <h3>权限点</h3>
 *
 * <p>读：{@code knowledge:view}；写：{@code knowledge:qa:edit}。 ★ 写不复用 {@code
 * knowledge:segment:edit}：分段是自动切出来的，问答对是人工标定的， 借用一个语义不相关的权限点会让"谁能改问答对"变成一个说不清的口径。
 */
@RestController
@RequestMapping("/api/v1")
public class QaPairController {

  private final QaPairService qaPairs;

  public QaPairController(QaPairService qaPairs) {
    this.qaPairs = qaPairs;
  }

  /**
   * 列出某个知识库的问答对（{@code FR-03-12-7}）。
   *
   * @param kbId 知识库（不可见 ⇒ 404）
   * @param page 页码，1 起
   * @param size 页大小
   */
  @GetMapping("/knowledge-bases/{kbId}/qa-pairs")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<PageResult<QaPairView>> list(
      @PathVariable UUID kbId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return ApiResponse.ok(qaPairs.list(kbId, page, size));
  }

  /**
   * 新建一条问答对（{@code FR-03-12-1} 的单条形态）。
   *
   * @param kbId 知识库
   * @param body 问题 / 答案 / 密级 / 可选出处
   * @param jwt 令牌（建档人取 {@code sub}，★ 不接受请求体传）
   */
  @PostMapping("/knowledge-bases/{kbId}/qa-pairs")
  @PreAuthorize("hasAuthority('knowledge:qa:edit')")
  public ApiResponse<QaPairView> create(
      @PathVariable UUID kbId,
      @RequestBody CreateQaPairBody body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(
        qaPairs.create(
            kbId,
            body.question(),
            body.answer(),
            body.classification(),
            body.segmentId(),
            subjectOf(jwt)));
  }

  /**
   * 批量新建（{@code FR-03-12-1} 的多行形态）：一行一个问题。
   *
   * <p>★ 走服务层既有的 {@link QaPairService#importPairs} —— 那一条的语义是 <strong>一批要么全进、要么一条都不进</strong>。★
   * 半批成功会让调用方无法判断 "重试会不会造出重复行"，而问答对没有业务唯一键。
   *
   * @param kbId 知识库
   * @param body 逐条 (问题, 答案, 密级, 出处?)
   * @param jwt 令牌
   */
  @PostMapping("/knowledge-bases/{kbId}/qa-pairs/batch")
  @PreAuthorize("hasAuthority('knowledge:qa:edit')")
  public ApiResponse<List<QaPairView>> batchCreate(
      @PathVariable UUID kbId,
      @RequestBody BatchCreateQaPairsBody body,
      @AuthenticationPrincipal Jwt jwt) {
    List<QaPairService.ImportPair> items =
        body.items() == null
            ? List.of()
            : body.items().stream()
                .map(
                    row ->
                        new QaPairService.ImportPair(
                            row.question(),
                            row.answer(),
                            row.segmentId(),
                            row.classification(),
                            // ★ 来源固定 MANUAL：从 web 手输的必然是人录入
                            cn.lincoln.aiplatform.knowledge.domain.QaPairOrigin.MANUAL,
                            null))
                .toList();
    return ApiResponse.ok(qaPairs.importPairs(kbId, items, subjectOf(jwt)));
  }

  /**
   * 改问题（{@code FR-03-12-2} 的行内改名）。
   *
   * @param id 问答对 id
   * @param body 只带新问题
   * @param jwt 令牌
   */
  @PatchMapping("/qa-pairs/{id}/question")
  @PreAuthorize("hasAuthority('knowledge:qa:edit')")
  public ApiResponse<QaPairView> rename(
      @PathVariable UUID id, @RequestBody RenameQaPairBody body, @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(qaPairs.rename(id, body.question(), subjectOf(jwt)));
  }

  /**
   * 删除问答对（{@code FR-03-12-3}）—— 软删，幂等。
   *
   * @param id 问答对 id
   * @param jwt 令牌
   */
  @DeleteMapping("/qa-pairs/{id}")
  @PreAuthorize("hasAuthority('knowledge:qa:edit')")
  public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    qaPairs.delete(id, subjectOf(jwt));
    return ApiResponse.ok(null);
  }

  /** 从 JWT 的 {@code sub} 取操作人。★ 与其它控制器同一口径，不接受前端传。 */
  private static UUID subjectOf(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  /**
   * 新建问答对的请求体。
   *
   * @param question 问题 ★ 必填
   * @param answer 答案 ★ 必填
   * @param classification 密级 ★ 必填且 ≤ 库上限
   * @param segmentId 出处（可空）
   */
  public record CreateQaPairBody(
      String question, String answer, ClassificationLevel classification, UUID segmentId) {}

  /**
   * 批量新建的请求体。
   *
   * @param items 逐条；★ 空清单 ⇒ 400（"什么都不做"不该返回成功）
   */
  public record BatchCreateQaPairsBody(List<Row> items) {

    /**
     * 一行。
     *
     * @param question 问题
     * @param answer 答案
     * @param classification 密级
     * @param segmentId 出处（可空）
     */
    public record Row(
        String question, String answer, ClassificationLevel classification, UUID segmentId) {}
  }

  /**
   * 改名的请求体。
   *
   * @param question 新问题
   */
  public record RenameQaPairBody(String question) {}
}

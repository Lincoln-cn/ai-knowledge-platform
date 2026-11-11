package cn.lincoln.aiplatform.knowledge.web;

import cn.lincoln.aiplatform.knowledge.application.EntityMergeService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实体归并端点（{@code §11.36.1} 的 D 项，0.6.8）。
 *
 * <p>★ 这里只做<strong>第二档</strong>：属性重叠的候选<b>只出清单、不自动合并</b>，由人逐条确认。
 *
 * <p>为什么第二档必须是人工：自动合并会直接改权限归属（两个实体分属不同组织时，并入哪一边就决定了谁能看到）， 而「属性重叠」只是<b>可能</b>相同 —— `{department:
 * 财务}` 的两个实体可以是「财务部」和「财务共享中心」。
 *
 * <p>★ 确认的动作是<b>写一条别名</b>，不是删一行：{@link EntityMergeService#recordAliases} 落 {@code
 * kb_entity_alias}，原实体行<b>保留</b>。合并必须可拆 —— 一旦删了原行，「这个别名是不是当初合并错了」 就无从回答。这条与 {@code
 * ConversationShareService} 的「撤销不删行」同一口径。
 *
 * <p>★ 权限点：{@code GET} ⇒ {@code knowledge:view}（看候选不会改数据）；{@code POST} ⇒ {@code knowledge:edit}
 * （写别名是改数据）。写端点<strong>必须</strong>带 {@code @PreAuthorize} —— {@code AuthorizationDeclarationTest}
 * 构建期检查，漏一个就红。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/entity-merge")
public class EntityMergeController {

  private final EntityMergeService mergeService;

  public EntityMergeController(EntityMergeService mergeService) {
    this.mergeService = mergeService;
  }

  /**
   * 第二档候选清单。
   *
   * @return 可能重复的实体对，每条带人可读的 {@code reason}
   */
  @GetMapping("/candidates")
  @PreAuthorize("hasAuthority('knowledge:view')")
  public ApiResponse<List<EntityMergeService.Candidate>> candidates(@PathVariable UUID kbId) {
    return ApiResponse.ok(mergeService.candidatesByAttributes(kbId));
  }

  /**
   * 确认一条合并：把 {@code mergeName} 记成 {@code keepId} 的别名。
   *
   * <p>★ 不删 {@code mergeId} 那一行 —— 见类注释。
   *
   * @return 实际新增的别名行数（0 表示这条别名已经记过，不是失败）
   */
  @PostMapping
  @PreAuthorize("hasAuthority('knowledge:edit')")
  public ApiResponse<MergeResult> merge(@PathVariable UUID kbId, @RequestBody MergeBody body) {
    int added = mergeService.recordManualAliases(kbId, body.keepId(), List.of(body.mergeName()));
    return ApiResponse.ok(new MergeResult(added));
  }

  /** 确认合并的请求体。 */
  public record MergeBody(UUID keepId, String mergeName) {}

  /** 确认合并的结果（{@code added} 为 0 是正常的幂等结果）。 */
  public record MergeResult(int added) {}
}

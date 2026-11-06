package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.RetentionDryRunService;
import cn.lincoln.aiplatform.conversation.application.RetentionDryRunService.DryRunReport;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 归档清理的 dry-run 端点（{@code #299}「留缝」）：按表报出「如果按留存期清理，会清掉什么」，<strong>只数不删</strong>。
 *
 * <p>★ 为什么单独一个 controller、不并进 {@code ChatController}：这个端点不是「某个会话」的动作，是生命周期管理面的只读试算；
 * 也刻意<strong>不是</strong> {@code POST}——它连"提交清理"的形状都不给，防"接口存在"被读成"清理已上线"。
 *
 * <p>★ 权限用 {@code conversation:manage}（管理他人会话）而不是 {@code
 * conversation:view}：它扫描的是<strong>全库</strong>过期软删会话， 不是"我的会话"——归属口径跟 {@code ChatController}
 * 的会话端点不同，权限点也要分得开。
 *
 * <p>★ 真删执行体今天不存在（{@code app.lifecycle.retention.*} 没有消费者）：天数是客户口径（02 §5.1.1 行 616
 * 「数字是口径不是承诺：真做清理任务前，必须与客户确认」），issue #299 没给数字 ⇒ 只留配置位 + 本端点 + 「今天没有任何东西会自动删」的断言。
 */
@RestController
@RequestMapping("/api/v1/conversations/retention")
public class ConversationRetentionController {

  private final RetentionDryRunService retentionDryRun;

  public ConversationRetentionController(RetentionDryRunService retentionDryRun) {
    this.retentionDryRun = retentionDryRun;
  }

  /**
   * 试算（{@code GET}，只读）：按留存期报出「若启用清理会清掉什么」，按表逐项给读数。不删任何东西。
   *
   * @param days 试算用的留存天数；缺省取配置位 {@code app.lifecycle.retention.retain-days}，两者都没有时拒绝（不编数字）
   */
  @GetMapping("/dry-run")
  @PreAuthorize("hasAuthority('conversation:manage')")
  public ApiResponse<DryRunReport> dryRun(
      @RequestParam(name = "days", required = false) Integer days) {
    return ApiResponse.ok(retentionDryRun.dryRun(days));
  }
}

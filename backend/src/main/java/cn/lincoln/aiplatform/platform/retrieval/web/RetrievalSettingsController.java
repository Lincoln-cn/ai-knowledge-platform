package cn.lincoln.aiplatform.platform.retrieval.web;

import cn.lincoln.aiplatform.platform.retrieval.RetrievalEngineSettings;
import cn.lincoln.aiplatform.platform.retrieval.RetrievalEngineSettingsService;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索引擎运行参数（配置页 {@code /settings/retrieval}）。
 *
 * <h3>★ 读写分开</h3>
 *
 * <ul>
 *   <li>读（GET）：登录即可。这几个值直接决定"一次问答要等多久"，排障时任何人都该看得到； 卡权限会让"我觉得今天特别慢"变成一句无法自证的话。
 *   <li>写（PUT）：{@code platform:retrieval:manage}。★ 不借 {@code system:manage}：改一次影响全平台 所有人的检索延迟与
 *       B1/B3 口径，与账号管理不是一类。
 * </ul>
 *
 * <h3>★ 为什么是一张整表提交，而不是逐格 PATCH</h3>
 *
 * <p>这几格互相牵连（窗口放大要配套留意批大小、关重试要配套确认 B3 口径）。逐格提交会让"中间状态" 落库并被别的请求读到 ——
 * 那正是"有的请求按新窗口、有的按旧窗口"的来源。整表提交的代价是客户端 必须先把当前值读全，但它是**可重试的一次写**。
 *
 * <h3>★ 不写 {@code updatedBy} 的鉴权语义</h3>
 *
 * <p>这格里存的是"谁改的"，不参与任何判断。真正的审计在 {@code iam_audit_log}（本方法的写操作会被 审计切面记下）——
 * 表里再存一份操作人只为了一眼能看到，不是第二套审计。
 */
@RestController
@RequestMapping("/api/v1/retrieval/settings")
public class RetrievalSettingsController {

  private final RetrievalEngineSettingsService settings;

  public RetrievalSettingsController(RetrievalEngineSettingsService settings) {
    this.settings = settings;
  }

  /** 当前生效的检索引擎参数。 */
  @GetMapping
  public ApiResponse<RetrievalEngineSettings> get() {
    return ApiResponse.ok(settings.current());
  }

  /** 保存检索引擎参数（{@code platform:retrieval:manage}）。 */
  @PutMapping
  @PreAuthorize("hasAuthority('platform:retrieval:manage')")
  public ApiResponse<RetrievalEngineSettings> update(
      @RequestBody RetrievalEngineSettingsService.UpdateCommand body,
      @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.ok(settings.update(body, UUID.fromString(jwt.getSubject())));
  }
}

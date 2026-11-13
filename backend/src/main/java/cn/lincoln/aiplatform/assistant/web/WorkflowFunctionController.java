package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.platform.assistant.FunctionInvokePort;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内置函数目录（契约 {@code GET /workflow/functions}）。
 *
 * <p>★ 为什么要下发这份清单：函数节点的面板得让人<strong>选</strong>函数名。若前端自己写一份清单，后端加一个函数 就得记着改两处 ——
 * 那正是本仓库反复付代价的形态（`#337`）。列表来自 {@link FunctionInvokePort#functionNames()}，
 * 也就是执行时真正会去查的那张表，面板与执行器因此不可能分叉。
 *
 * <p>★ 端口缺失时返回空表而不是报错：函数节点在"没装配函数能力"时本来就跑不了，但面板打不开会让用户以为 整个画布坏了 —— 空表 + 面板允许手填函数名，比 500 更接近事实。
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowFunctionController {

  private final ObjectProvider<FunctionInvokePort> functions;

  public WorkflowFunctionController(ObjectProvider<FunctionInvokePort> functions) {
    this.functions = functions;
  }

  /** 函数名清单（已排序，界面直接渲染）。 */
  @GetMapping("/functions")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<List<String>> list() {
    FunctionInvokePort port = functions.getIfAvailable();
    if (port == null) {
      return ApiResponse.ok(List.of());
    }
    return ApiResponse.ok(port.functionNames().stream().sorted().toList());
  }
}

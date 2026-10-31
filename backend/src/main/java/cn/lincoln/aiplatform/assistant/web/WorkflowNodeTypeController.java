package cn.lincoln.aiplatform.assistant.web;

import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 节点目录（契约 {@code GET /workflow/node-types}，{@code #198} 第二块）。
 *
 * <p>★ 为什么由后端下发：18 个节点平铺成一列找不动，必须分组；而"哪个节点属于哪一组、 实现了没有"是<strong>目录数据</strong> ——
 * 前端再抄一份，新增节点时必然漏改一处。
 *
 * <p>★ 数据放 {@code resources/workflow/node-types.json} 而不是硬编码：它会按类型被查表 （发布前校验）、文案也可能由运营调整 ——
 * 数据和代码分开，改文案不用动编译单元。
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowNodeTypeController {

  private static final String RESOURCE = "workflow/node-types.json";

  private final ObjectMapper objectMapper;

  public WorkflowNodeTypeController(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** 全部节点（含未实现的）；未实现者 {@code implemented=false}，界面据此标「规划中」。 */
  @GetMapping("/node-types")
  @PreAuthorize("hasAuthority('assistant:view')")
  public ApiResponse<List<NodeTypeMeta>> list() throws IOException {
    try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
      return ApiResponse.ok(objectMapper.readValue(in, new TypeReference<List<NodeTypeMeta>>() {}));
    }
  }

  /** 目录条目（契约 {@code NodeTypeMeta}）。 */
  public record NodeTypeMeta(
      String type, String label, String description, String category, boolean implemented) {}
}

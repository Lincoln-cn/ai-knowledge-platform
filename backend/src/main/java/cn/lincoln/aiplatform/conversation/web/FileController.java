package cn.lincoln.aiplatform.conversation.web;

import cn.lincoln.aiplatform.conversation.application.FileService;
import cn.lincoln.aiplatform.conversation.application.FileView;
import cn.lincoln.aiplatform.platform.web.ApiResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 会话附件上传（契约 {@code uploadFile}，{@code 08 §4.9}）。
 *
 * <h3>为什么路径是 {@code /files} 而不是 {@code /conversations/{id}/files}</h3>
 *
 * <p>上传发生在<strong>会话还没有 id</strong> 的时候（用户在输入框里贴文件，会话可能还没创建）。所以路径不能带会话 id， 归属关系要等到提问那一刻由 {@code
 * attachmentIds} 建立。
 *
 * <h3>★★ 今天能做到哪一步（诚实边界）</h3>
 *
 * <p>这个端点把字节存好、元数据记下、返回 {@code {id,name,size,mime?}}。<strong>然后就结束了</strong>： {@code
 * ChatController} 收下 {@code attachmentIds} 但<strong>不消费</strong>，所以"上传一份文档再问关于它的问题"这条路径
 * <strong>今天走不通</strong> —— 检索不到它。绑定与入库是下一批（{@code 13 §11.2–11.4.1}）。
 * 写在这里的理由：把"能上传"读成"附件能用了"的人，会先怀疑检索、再怀疑模型，最后才发现这层没接线。
 *
 * <h3>★ 表单字段名是 {@code file}</h3>
 *
 * <p>与前端 {@code uploadFile} 的 {@code form.append('file', f)} 对齐。字段名对不上时 Spring 会给一个 "Required part
 * 'file' is not present"，比自定义文案更有用，所以这里不加 {@code required = false} 再自造一段。
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

  private final FileService files;

  public FileController(FileService files) {
    this.files = files;
  }

  /**
   * 上传一个聊天附件。
   *
   * <p>★ 权限用 {@code conversation:view}：附件是问答链路的一部分，能提问的人就能传附件；单独开一个 {@code file:upload}
   * 权限点会让"能聊但不能传图"这种组合出现在权限矩阵里，而今天没有任何业务规则需要它。
   *
   * @param file multipart 文件（字段名 {@code file}）
   * @param jwt 当前用户，用于记 {@code owner_id}
   * @return 附件视图；ids 由前端挂到下一轮请求的 {@code attachmentIds}
   * @throws IOException 读取 multipart 字节失败（客户端中途断开）
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasAuthority('conversation:view')")
  public ApiResponse<FileView> upload(
      @RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt)
      throws IOException {
    return ApiResponse.ok(
        files.upload(
            file.getOriginalFilename(),
            file.getContentType(),
            file.getBytes(),
            UUID.fromString(jwt.getSubject())));
  }
}

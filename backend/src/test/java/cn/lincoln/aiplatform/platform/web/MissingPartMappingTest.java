package cn.lincoln.aiplatform.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 缺少 multipart 表单部分（如知识库上传少带 `files`）必须映射成 400，不是 500。
 *
 * <h3>为什么这条判据必须存在</h3>
 *
 * <p>实测（2026-11-20，`#358`）：`POST /knowledge-bases/{kb}/documents` 少带 `files` 这个 part 时， 界面收到的是
 *
 * <pre>500 {"message":"服务内部错误","hint":"请把这次操作的时间反馈给管理员，日志里有对应的堆栈"}</pre>
 *
 * <p>而实际原因只是**调用方把字段名写成了单数** `file`（会话附件那条路（`POST /files`）就叫 `file`）。
 * 于是"我自己拼错了"被报成"服务端故障"——调用方去重启、去查日志、去找管理员， 而正确的动作是改一个字母。这与 {@code UploadSizeLimitTest} 记录的那次 「超限报
 * 500 导致无限重试」是同一族：<b>状态码决定了人的下一步动作</b>。
 *
 * <p>根因是 {@code GlobalExceptionHandler} 只映射了查询参数缺失 （{@code
 * MissingServletRequestParameterException}），漏了表单 part 缺失 （{@code
 * MissingServletRequestPartException}）—— 两个类名字很像、抛的地方不同。
 *
 * <p>★ 金丝雀：删掉 {@code onMissingPart} 那条 {@code @ExceptionHandler}，第二条必红 （会走兜底的 500）。
 */
class MissingPartMappingTest {

  /** 处理器没有任何构造依赖（只有一个 static Logger），所以不必起 Spring 上下文。 */
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void missingPartIsBadRequestNotServerError() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.onMissingPart(new MissingServletRequestPartException("files"));

    assertThat(response.getStatusCode())
        .as("★★ 必须 400：这是调用方能自己修的输入问题，报 500 会让他去查服务端")
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void theMessageNamesThePartSoTheCallerCanSeeTheirTypo() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.onMissingPart(new MissingServletRequestPartException("files"));

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message())
        .as("★ 要回显 part 名 —— 调用方靠它对照『我传的到底是哪一个名字』")
        .contains("files");
    assertThat(response.getBody().hint())
        .as("★ 单复数这处不一致是真实存在的坑（file vs files），hint 里点出来才能少一次往返")
        .contains("file");
  }
}

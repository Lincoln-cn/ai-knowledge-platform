package cn.lincoln.aiplatform.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.web.ApiResponse;
import cn.lincoln.aiplatform.platform.web.GlobalExceptionHandler;
import cn.lincoln.aiplatform.support.PostgresImage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * ★★ 上传体积上限的判据（`FR-03-05-3`：单文件 100MB，超限拒绝并提示）。
 *
 * <h3>为什么这条判据必须存在</h3>
 *
 * <p>实测过一个很难查的现场：16 个语料文件上传，**≤662KB 全部成功、≥1368KB 全部 HTTP 500**。 两个失真叠在一起：
 *
 * <ul>
 *   <li><b>限额错了 100 倍</b>：Spring Boot 的 multipart 默认 **1MB**，而设计书写的是 **100MB**；
 *   <li><b>状态码误导</b>：没有 {@code MaxUploadSizeExceededException} 的处理 ⇒ 兜底成 <b>500</b>，
 *       调用方会以为"服务端坏了"从而**重试** —— 而重试永远不会成功。
 * </ul>
 *
 * <p>⇒ 本类钉两条：<b>配置真的是 100MB</b>（不是默认 1MB）、<b>超限映射成 413 + 可读原因</b>。
 *
 * <p>★ 为什么不起 HTTP：{@code MaxUploadSizeExceededException} 在**进入控制器之前**就已判出，
 * 所以"处理器把它映射成什么"就是全部语义；为再证一次而构造 101MB 请求体，代价与收益不成比例。
 *
 * <p>★ 金丝雀：把 {@code onUploadTooLarge} 的状态码改成 500 ⇒ 第二条必红。
 */
@SpringBootTest
@Testcontainers
class UploadSizeLimitTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  /** 上限读的是**配置本身** —— 判据与配额同源，改默认值就必须同步改断言。 */
  @Value("${spring.servlet.multipart.max-file-size}")
  String configuredMaxFileSize;

  @Autowired GlobalExceptionHandler handler;

  @Test
  void configuredLimitIsNotTheOneMegabyteDefault() {
    assertThat(configuredMaxFileSize)
        .as(
            "★★ 上限必须显式配置。缺省时 Boot 是 **1MB**，而 `FR-03-05-3` 写的是 **100MB** ——"
                + " 实测 16 个语料里 ≥1368KB 全部 500，就是这个缺口")
        .isEqualTo("100MB");
  }

  @Test
  void oversizedUploadIsMappedTo413WithAReadableReason() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.onUploadTooLarge(new MaxUploadSizeExceededException(100L * 1024 * 1024));

    assertThat(response.getStatusCode())
        .as(
            "★★ 必须 413 而不是 500 —— 500 会让调用方以为服务端坏了并**重试**，而重试永远不会成功"
                + "（这条在加 `onUploadTooLarge` 之前是 500）")
        .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).as("★ 原因要能看出『是太大了』，不是『服务器内部错误』").contains("超过大小上限");
    assertThat(response.getBody().hint()).as("★ hint 要说明重试不会成功 —— 那是 500 最坏的后效应").contains("重试");
  }
}

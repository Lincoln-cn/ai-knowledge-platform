package cn.lincoln.aiplatform.conversation.web;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.web.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 聊天端点在"流还没开始"时的错误响应形状（{@code #373}）。
 *
 * <h3>★★ 为什么这条测试值得单独存在</h3>
 *
 * <p>同一个业务错误，{@code Accept: application/json} 与不带 Accept 时是 {@code 400 + 完整包络}， 而 {@code Accept:
 * text/event-stream} 时曾是 {@code 500 + 0 字节} —— 因为错误响应的媒体类型回到"按 Accept 协商"，而这条链路的 Accept 只有 {@code
 * text/event-stream}。前端 {@code lib/sse.ts} 恰好只发这一个 Accept ⇒
 * 用户在"对一条已答完的回答点继续"时只看到"生成失败"，看不到"只有已停止的回答可以续写"。
 *
 * <p>⇒ 钉住的不是"状态码是 400"，而是<strong>响应上显式写着 {@code application/json}</strong>： 只要少了它，协商就会重新接管，而在 SSE
 * 客户端上结果是 500 空体。
 */
class ChatControllerErrorShapeTest {

  /**
   * ★ 故意用全 null 构造：这条测试只碰 {@link ChatController#onIllegalState}，它不读任何协作者字段。 用 Spring
   * 上下文去测这一条，会把一个"一个注解有没有写"的问题变成"容器起不起得来"的问题。
   */
  private static ChatController controller() {
    return new ChatController(null, null, null, null, null, null, null, null, null);
  }

  @Test
  @DisplayName("流还没开始的领域守卫错误：400 + 显式 application/json + 原样原因")
  void illegalStateIsRenderedAsJson() {
    String reason = "只有「已停止」的回答可以续写（这条消息现在 GENERATED）—— 答完的应当重新提问，失败过的应当重新生成";

    ResponseEntity<ApiResponse<Void>> response =
        controller().onIllegalState(new IllegalArgumentException(reason));

    assertThat(response.getStatusCode())
        .as("领域守卫抛的是 IllegalArgumentException ⇒ 400（不是 500：那是「服务端坏了」的意思，会让人去重试）")
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType())
        .as("★ 必须是显式给出的 application/json —— 少了它，Accept 只有 text/event-stream 的客户端会收到 500 空体")
        .isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).as("包络本体不能是空的").isNotNull();
    assertThat(response.getBody().message()).as("原因要原样透出：用户看到这句话才知道下一步该做什么").isEqualTo(reason);
  }

  @Test
  @DisplayName("非法状态同样按这个形状返回 —— 两条守卫不能只有一条能被读到")
  void illegalStateClassAlsoIsRenderedAsJson() {
    ResponseEntity<ApiResponse<Void>> response =
        controller().onIllegalState(new IllegalStateException("某某状态不允许这个动作"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().message()).isEqualTo("某某状态不允许这个动作");
  }
}

package cn.lincoln.aiplatform.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;
import org.junit.jupiter.api.Test;

/**
 * 响应包络与错误码（{@code ROADMAP §4.7.3} 第 1 周测试项 ④）。
 *
 * <p>★ 这个测试的价值在于<strong>把冻结契约抄成断言</strong>： 下面每个数字都来自 {@code frontend/src/api/types.ts} 的 {@code
 * ErrorCode}。 谁改了 Java 侧的值，这里立刻红 —— 而"后端悄悄多返回一个码"恰恰是前端最难排查的一类问题 （它只会在某个 render 分支里表现为空白）。
 */
class ApiResponseTest {

  @Test
  void 成功包络的码为零且文案为ok() {
    ApiResponse<String> res = ApiResponse.ok("内容");

    assertThat(res.code()).isEqualTo(0);
    assertThat(res.message()).isEqualTo("ok");
    assertThat(res.data()).isEqualTo("内容");
    assertThat(res.hint()).isNull();
    assertThat(res.isOk()).isTrue();
  }

  @Test
  void 无数据体的成功仍然是完整包络() {
    ApiResponse<Void> res = ApiResponse.ok();

    assertThat(res.code()).isZero();
    assertThat(res.data()).isNull();
  }

  /**
   * ★ 逐项对照 {@code frontend/src/api/types.ts:27-40}。
   * 这八个值是<strong>契约已冻结</strong>的部分；新增错误码的正确顺序是"先改契约，再改枚举"。
   */
  @Test
  void 错误码必须与前端契约逐项一致() {
    assertThat(ErrorCode.OK.code()).isZero();
    // ★ BAD_REQUEST(400) 是第 2 周做端点时补的：契约的 ErrorCode 里还没有它，
    //   而请求体校验失败总得有个码。它是 401/403/404 那三个"与 HTTP 对齐"的码的同族补全。
    //   CHANGELOG 0.1.0 记了"契约该补上这一项"。
    assertThat(ErrorCode.BAD_REQUEST.code()).isEqualTo(400);
    assertThat(ErrorCode.UNAUTHORIZED.code()).isEqualTo(401);
    assertThat(ErrorCode.FORBIDDEN.code()).isEqualTo(403);
    assertThat(ErrorCode.NOT_FOUND.code()).isEqualTo(404);
    assertThat(ErrorCode.MODEL_NOT_READY.code()).isEqualTo(2001);
    assertThat(ErrorCode.EMBEDDING_LOCKED.code()).isEqualTo(3001);
    assertThat(ErrorCode.SEGMENT_TOO_LONG.code()).isEqualTo(3002);
    assertThat(ErrorCode.PUBLISH_CHECK_FAILED.code()).isEqualTo(6001);
  }

  @Test
  void 业务异常经from保真透出码_文案与hint() {
    BusinessException e =
        new BusinessException(ErrorCode.SEGMENT_TOO_LONG, "分段 12 超出上限 2000 字", "把该段拆小，或调大分段上限");

    ApiResponse<Void> res = ApiResponse.from(e);

    assertThat(res.code()).isEqualTo(3002);
    assertThat(res.message()).isEqualTo("分段 12 超出上限 2000 字");
    assertThat(res.hint()).isEqualTo("把该段拆小，或调大分段上限");
    assertThat(res.data()).isNull();
    assertThat(res.isOk()).isFalse();
  }

  /**
   * ★ 契约上最容易做错的一条：<strong>看不见 = 404，不是 403</strong>（{@code 08 §4.0} 第 4 行）。
   *
   * <p>返 403 等于承认"这个 ID 真实存在，只是你没权限"—— 对越权探测来说这是有用信息。 所以这里不是测代码，是<strong>把这条产品决定钉在测试里</strong>，
   * 免得后来有人在某个 controller 里"顺手"改成更精确的 403。
   */
  @Test
  void 资源不可见必须返404而非403() {
    BusinessException invisible =
        new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在", "它可能已被删除，或你没有访问权限");

    assertThat(ApiResponse.from(invisible).code()).as("返 403 会泄漏'该资源存在'这一事实").isEqualTo(404);
  }

  @Test
  void 失败时不省略data位() {
    ApiResponse<Void> res = ApiResponse.fail(ErrorCode.FORBIDDEN, "无权限", "请联系管理员为你的角色勾选该权限点");

    assertThat(res.data()).as("契约里 data 是必填位；省略它会逼前端写防御代码").isNull();
    assertThat(res.hint()).isEqualTo("请联系管理员为你的角色勾选该权限点");
  }

  @Test
  void 按契约数值码可以反查() {
    assertThat(ErrorCode.fromCode(3001)).isEqualTo(ErrorCode.EMBEDDING_LOCKED);
    assertThat(ErrorCode.fromCode(999999)).isNull();
  }
}

package cn.lincoln.aiplatform.assistant.domain;

import cn.lincoln.aiplatform.platform.domain.BusinessException;
import cn.lincoln.aiplatform.platform.domain.ErrorCode;

/**
 * 凭证本身的状态不允许这个动作（例如"已撤销的凭证重新启用"）。
 *
 * <p>★ 它是 {@link BusinessException} 的子类而不是一个新错误码：契约里的错误码是冻结的 （{@code
 * frontend/src/api/types.ts}），本轮<strong>不许新增</strong>（新增要动前后端一致性闸门）。 继承之后统一处理器照样给出 {@code
 * {code,message,data,hint}} 包络， 而"到底哪一种状态"由<strong>文案</strong>承载 （{@code 08 §4.1} 的口径：码管分支、文案管解释）。
 *
 * <p>★ 这一类的调用方是<strong>管理员</strong>（他在凭证管理页上点了一个不该点的按钮）， 所以码取 {@code
 * BAD_REQUEST}：他的动作在当前状态下不合法，改一下就能成 —— 而不是 {@code FORBIDDEN}（那要去找人开权限）。
 */
public class InvalidCredentialException extends BusinessException {

  public InvalidCredentialException(String message) {
    super(ErrorCode.BAD_REQUEST, message, "撤销是终态，只能重新生成一条凭证");
  }
}

/**
 * 身份与权限（identity）—— 账号、角色、权限点、认证与授权。
 *
 * <p>★ 它同时是"数据范围判定"的<strong>唯一实现</strong>（{@code 08 §4.0} 第 4 行）： {@code AccessScopePort}
 * 的实现只允许出现在这里，"别处再实现一份"必须是构建失败。
 *
 * <p>依赖：{@code platform}。矩阵见 {@code 01 §三.2}。
 */
@ApplicationModule(allowedDependencies = {"platform"})
package cn.lincoln.aiplatform.identity;

import org.springframework.modulith.ApplicationModule;

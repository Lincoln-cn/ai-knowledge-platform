/**
 * 《共享内核》（platform）—— 模块化单体的公共底座。
 *
 * <p>★ 这里声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}，而其余 11 个模块都是 {@code
 * CLOSED}。理由：01 §六.1 给 platform 的定位是"不属于任何业务域"的共享内核 ——
 * 值对象基类、业务异常、ID、时钟、文件存储端口都是<strong>跨模块直接使用的类型</strong>， 若按业务模块那样只暴露一个 {@code api}
 * 包，等于给每个共享类型都套一层无意义的包装。
 *
 * <p>依赖规则见 {@code docs/系统设计方案/01-架构设计（DDD·Spring Modulith）.md} 三.2 的矩阵；
 * 本模块<strong>无业务依赖</strong>（矩阵首行全空），由 {@code ModuleBoundaryTest} 强制校验。
 *
 * <p>★ 同时由 {@code PlatformPurityTest} 保证它<strong>不沾染 AI 与响应式类型</strong> （{@code
 * org.springframework.ai.*} / {@code reactor.*}）—— 共享内核一旦依赖具体模型实现， 换模型就等于改内核，而且会把 Reactor 泄漏到所有模块。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package cn.lincoln.aiplatform.platform;

import org.springframework.modulith.ApplicationModule;

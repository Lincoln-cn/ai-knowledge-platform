package canaryfixture.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ⚠️ 金丝雀样本：一个<strong>忘了声明权限</strong>的写端点。
 *
 * <p>它存在的唯一目的，是让 {@code AuthorizationDeclarationTest} 能断言
 * "漏声明权限这件事确实会被抓住"。它<strong>不该被"修好"</strong> —— 你给它补上 {@code @PreAuthorize} 的那一刻，那条金丝雀就永久失效了，
 * 而失效的方式是**静默**的（测试仍然绿）。
 *
 * <p>放在 {@code canaryfixture} 顶层包而不是应用包下：Spring Boot 的组件扫描 从启动类所在的包开始，放进去会真的注册成一个端点。
 */
@RestController
public class UnguardedController {

  @PostMapping("/canary/unguarded")
  public String create() {
    return "ok";
  }
}

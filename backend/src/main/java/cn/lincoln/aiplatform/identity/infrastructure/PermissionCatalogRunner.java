package cn.lincoln.aiplatform.identity.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时同步权限点目录（{@code 08 §4.3.1}）。
 *
 * <p>★ 用 {@link ApplicationRunner} 而不是 {@code @PostConstruct}：前者在<strong>上下文完全就绪 之后</strong>执行，此时
 * Flyway 已经跑完、所有 bean 都能用；后者可能在 Flyway 之前触发， 那会撞上"表还不存在"。
 *
 * <p>★ 它失败时的行为是**启动失败**（异常会中断启动），这是刻意的： 一个"权限点没同步"就启动成功的系统，会带着一份与代码不一致的权限清单对外服务。
 */
@Component
public class PermissionCatalogRunner implements ApplicationRunner {

  private final PermissionCatalog catalog;

  public PermissionCatalogRunner(PermissionCatalog catalog) {
    this.catalog = catalog;
  }

  @Override
  public void run(ApplicationArguments args) {
    catalog.sync();
  }
}

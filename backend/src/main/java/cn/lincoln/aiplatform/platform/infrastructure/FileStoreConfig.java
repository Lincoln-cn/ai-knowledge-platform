package cn.lincoln.aiplatform.platform.infrastructure;

import cn.lincoln.aiplatform.platform.domain.FileStorePort;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储端口的装配（{@code #187} 的 {@code POST /files}）。
 *
 * <p>★ 显式建 bean 而不是 {@code @Component}：它的构造参数是一个<strong>磁盘目录</strong>， 在这里一眼能看到"这东西会往哪写"—— 与 {@code
 * CryptoConfig} 同一个理由（扫包扫出来的安全感是假的）。
 *
 * <p>★ 配置默认值写在 {@code @Value} 的冒号后面，同时也在 {@code application.yml} 里声明（{@code check_config_keys.py}
 * 要求 {@code app.*} 的每个键都有声明）：两处同源，改一处不改另一处会被闸门点出来。
 */
@Configuration
public class FileStoreConfig {

  /**
   * 本地磁盘实现。
   *
   * <p>★ 冒号后的默认值不是"顺手写的兜底"：{@code @SpringBootTest} 会在**没有** {@code application.yml} 的
   * 环境里起上下文（例如只加载部分配置的测试切面），一个没有默认值的 {@code app.*} 键会让**每个**测试类都
   * 在起步时炸掉，而报错信息是"占位符解析失败"——指向配置、不指向被测代码。声明同时在 {@code application.yml} 里（{@code
   * check_config_keys.py} 守着），两处同源。
   *
   * @param root 存储根目录；相对路径按进程工作目录解析（{@code deploy/} 下的启动脚本会把它设成持久卷的挂载点）
   */
  @Bean
  FileStorePort fileStorePort(@Value("${app.files.root:data/files}") String root) {
    return new LocalFileStore(Path.of(root));
  }
}

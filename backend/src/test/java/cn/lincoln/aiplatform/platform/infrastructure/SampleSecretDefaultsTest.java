package cn.lincoln.aiplatform.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 示例密钥常量必须与 `application.yml` 的默认值**逐字相同**（`#272`）。
 *
 * <p>★ 为什么需要这条：{@link StartupSettingsCheck#secretProblems} 靠"值等于示例值"判断 "生产忘了换密钥"。这个判断成立的前提是常量真的等于
 * yml 里的默认值 —— 若有人改了 yml 默认值 却忘了改常量，自检会**安静地不再报警**，而部署方看到的仍是一行"配置自检通过"。
 *
 * <p>★ 『自检安静地失效』比没有自检更坏（与 `#270` 同族：读数看起来是绿的，量到的却不是那件事）。 所以这条测试读的是**仓库里的 yml 文件本身**，不是常量数组的自洽。
 */
class SampleSecretDefaultsTest {

  private static final Path APPLICATION_YML =
      Path.of("src", "main", "resources", "application.yml");

  @Test
  void theSampleJwtSecretMatchesTheYmlDefault() {
    assertThat(readYml())
        .as("yml 里的默认值必须包含这个常量 —— 否则『还是示例值』永远判不出来")
        .contains("${JWT_SECRET:" + StartupSettingsCheck.SAMPLE_JWT_SECRET + "}");
  }

  @Test
  void theSampleApiKeySecretMatchesTheYmlDefault() {
    assertThat(readYml())
        .as("同上：api-key-secret 的默认值必须与常量逐字相同")
        .contains("${MODEL_API_KEY_SECRET:" + StartupSettingsCheck.SAMPLE_API_KEY_SECRET + "}");
  }

  @Test
  void theTwoSecretsAreDifferent() {
    assertThat(StartupSettingsCheck.SAMPLE_JWT_SECRET)
        .as("两个密钥用途不同（签令牌 vs 加模型 Key），复用一份示例值会让人以为『换一个就都换了』")
        .isNotEqualTo(StartupSettingsCheck.SAMPLE_API_KEY_SECRET);
  }

  private static String readYml() {
    try {
      return Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("读不到 application.yml：" + APPLICATION_YML, e);
    }
  }
}

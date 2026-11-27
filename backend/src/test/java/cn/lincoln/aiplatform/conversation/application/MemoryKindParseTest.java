package cn.lincoln.aiplatform.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.conversation.domain.MemoryKind;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * `#359`：模型给了一个不认识的种类时，不能把整轮抽取打断。
 *
 * <p>实测（真栈闸门日志多次）`未知的记忆种类：memories-1.kind` —— 模型偶尔把 schema 路径当值抄回来。 此前 {@code MemoryKind.of}
 * 直接抛，被外层 {@code catch (RuntimeException)} 接住 ⇒ 这一轮抽出的 其它记忆**一起丢掉**，日志只有一句「抽取记忆失败」。
 *
 * <p>这里钉的是判据本身：不认识的种类要能被<strong>跳过</strong>并留下痕迹（哪几条坏、坏成什么样）， 而不是把整轮归零。
 */
class MemoryKindParseTest {

  @Test
  void unknownKindIsSkippedInsteadOfThrowing() {
    Set<String> bad = new TreeSet<>();

    Optional<MemoryKind> parsed = MemoryExtractor.kindOrEmpty("memories-1.kind", bad);

    assertThat(parsed).as("★ 不认识的种类必须返回空，而不是抛异常 —— 抛出去会被外层 catch 成「整轮不留记忆」").isEmpty();
    assertThat(bad).as("★ 坏值要留痕：轮末那条 warn 就是靠它说出「有几条坏、坏成什么样」").containsExactly("memories-1.kind");
  }

  @Test
  void knownKindStillParses() {
    Set<String> bad = new TreeSet<>();

    Optional<MemoryKind> parsed = MemoryExtractor.kindOrEmpty("preference", bad);

    assertThat(parsed).contains(MemoryKind.PREFERENCE);
    assertThat(bad).as("★ 对照：正常的种类不该被记成坏值").isEmpty();
  }

  @Test
  void nullAndBlankAreBadValuesNotCrashes() {
    Set<String> bad = new TreeSet<>();

    assertThat(MemoryExtractor.kindOrEmpty(null, bad)).isEmpty();
    assertThat(MemoryExtractor.kindOrEmpty("  ", bad)).isEmpty();
    assertThat(bad).as("★ 两种空值都要能被跳过，且各自留痕（null 记成字符串 \"null\"，方便日志里区分）").isNotEmpty();
  }
}

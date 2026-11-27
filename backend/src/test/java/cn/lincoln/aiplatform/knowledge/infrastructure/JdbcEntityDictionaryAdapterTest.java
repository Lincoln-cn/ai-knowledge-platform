package cn.lincoln.aiplatform.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内置字典的判据（0.9.9）。
 *
 * <p>★ 这几条盯的不是"抽取器怎么抽"，而是"内置词表到底把哪个词映射成哪一类" —— `#377` 就是这么来的：映射写反了，抽取器一点错都没有，界面却把「上级包含下级」显示成「隶属」。
 */
class JdbcEntityDictionaryAdapterTest {

  private final Map<String, String> marks = JdbcEntityDictionaryAdapter.builtin().relationMarks();

  @Test
  @DisplayName("★ #377：「下属」必须是 CONTAINS（上级包含下级），不能是 BELONGS_TO")
  void subordinateMeansContainsNotBelongsTo() {
    assertThat(marks).containsEntry("下属", "CONTAINS");
  }

  @Test
  @DisplayName("七类受控关系在内置词表里都有落点")
  void everyRelationshipTypeHasAMark() {
    assertThat(marks.values())
        .contains(
            "BELONGS_TO",
            "CONTAINS",
            "PART_OF",
            "RESPONSIBLE_FOR",
            "DEFINES",
            "WORKS_AT",
            "REFERENCES");
  }

  @Test
  @DisplayName("刻意不内置单字「任」：它会把「责任」「任务」「任何」都当成任职")
  void noSingleCharacterWorkMark() {
    assertThat(marks).doesNotContainKey("任");
    assertThat(marks).containsKeys("担任", "任职于", "就职于");
  }
}

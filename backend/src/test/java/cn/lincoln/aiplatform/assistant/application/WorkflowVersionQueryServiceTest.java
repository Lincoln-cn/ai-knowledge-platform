package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lincoln.aiplatform.assistant.domain.WorkflowVersion;
import cn.lincoln.aiplatform.assistant.domain.WorkflowVersionRepository;
import cn.lincoln.aiplatform.identity.infrastructure.UserDisplayNameAdapter;
import cn.lincoln.aiplatform.platform.access.UserDisplayNamePort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 发布历史的"名字这一列"：<strong>兜底分支</strong>的反证（{@code 8d9d426} 留下的缺口）。
 *
 * <p>★ 这里测的不是"名字能显示出来"（那是 {@code WorkflowVersionHttpTest} 的真栈测试）， 而是
 * <strong>名字取不到时列表还能不能出来</strong>。上一版把 {@code catch (RuntimeException)} 写在 {@code
 * WorkflowVersionQueryService} 里，而那个分支没有任何测试能触发它 —— 要么造 DDL 故障，要么 mock {@code
 * JdbcTemplate}，都等于"为测试改形状"。抽成 {@link UserDisplayNamePort} 之后，兜底只剩实现里那一个 {@code catch}，于是"故意删掉它 ⇒
 * 本类变红"这件事成立了。
 *
 * <p>★ 因此第一个测试的方法名里写的是 {@code listStillReturnsRows…}：断言的对象是<strong>列表</strong>， 名字只是"可以缺失的那一列"。
 * 一个把"名字查不到"升级成"整页 500"的实现，会让所有原本只想要版本号的人一起看不到东西。
 */
class WorkflowVersionQueryServiceTest {

  private static final UUID APP = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final UUID PUBLISHER = UUID.fromString("00000000-0000-4000-8000-0000000000b1");
  private static final ObjectMapper JSON = new ObjectMapper();

  private WorkflowDraftService drafts;
  private WorkflowVersionRepository versions;

  @BeforeEach
  void setUp() {
    // requireAssistant 是 void 的包内方法：mock 默认什么都不做，正好是"应用存在"
    drafts = mock(WorkflowDraftService.class);
    versions = mock(WorkflowVersionRepository.class);
  }

  /**
   * ★ 本批的目的：名字查询整体失败时，{@code list} 仍然返回版本列表，每条 {@code publishedByName} 为 {@code null}，且不抛。
   *
   * <p>失败注入点刻意选在 {@link NamedParameterJdbcTemplate}：真实的 {@link UserDisplayNameAdapter} + 一个一定会抛的
   * jdbc，等价于"让端口走失败路径"。这样断言覆盖的是<strong>真实的兜底代码</strong>，不是一个为测试编出来的假端口 —— 假端口抛异常只能证明"service 没包
   * try"，证不了端口自己兜住了。
   *
   * <p>★ 金丝雀就是删掉 {@code UserDisplayNameAdapter} 里那个 {@code catch} 再跑本方法：它会红在一个 {@code
   * IllegalStateException} 上。反证记录在提交消息里。
   */
  @Test
  void listStillReturnsRowsWhenDisplayNameLookupFails() {
    when(versions.history(APP)).thenReturn(List.of(row(2, PUBLISHER), row(1, PUBLISHER)));

    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    doThrow(new IllegalStateException("模拟 iam_user 查询失败：连接中途被断"))
        .when(jdbc)
        .query(anyString(), anyMap(), any(RowCallbackHandler.class));

    WorkflowVersionQueryService service =
        new WorkflowVersionQueryService(drafts, versions, JSON, new UserDisplayNameAdapter(jdbc));

    List<WorkflowVersionQueryService.Version> history = service.list(APP);

    assertThat(history).as("★ 名字查不到不许把整页发布历史打挂").hasSize(2);
    assertThat(history)
        .extracting(WorkflowVersionQueryService.Version::publishedByName)
        .as("★ 解析不到 = null（前端显示「（未知用户）」），不是 uuid、不是空串、不是整页 500")
        .containsOnlyNulls();
    assertThat(history)
        .extracting(WorkflowVersionQueryService.Version::publishedBy)
        .as("id 照旧给 —— 名字只是给它配一个可读的标签")
        .containsOnly(PUBLISHER);
    assertThat(history)
        .extracting(WorkflowVersionQueryService.Version::version)
        .as("其余列不受影响：列表的顺序与内容还是发布历史本身")
        .containsExactly(2, 1);
  }

  /**
   * 端口返回的名字要<strong>真的</strong>接到对应那一行上。
   *
   * <p>★ 这条与上一条成对：只有"失败时全 null"没有"成功时有名字"，一个把名字列写死成 {@code null} 的实现也能让上一条绿。 用 lambda 假端口（而不是再 mock
   * 一次 jdbc）是因为这里要证的是 service 的<strong>接线</strong>，与 SQL 无关。
   */
  @Test
  void listFillsNamesFromThePort() {
    when(versions.history(APP)).thenReturn(List.of(row(1, PUBLISHER)));
    UserDisplayNamePort names = ids -> Map.of(PUBLISHER, "张三");

    WorkflowVersionQueryService service =
        new WorkflowVersionQueryService(drafts, versions, JSON, names);

    assertThat(service.list(APP))
        .singleElement()
        .satisfies(
            item -> assertThat(item.publishedByName()).as("端口按 id 给回来的名字，要落在同一行上").isEqualTo("张三"));
  }

  private static WorkflowVersion row(int number, UUID publisher) {
    return new WorkflowVersion(
        UUID.randomUUID(),
        APP,
        number,
        "{\"nodes\":[{\"id\":\"n1\"},{\"id\":\"n2\"}],\"edges\":[]}",
        "第 " + number + " 版",
        publisher,
        Instant.parse("2026-10-31T10:0" + number + ":00Z"));
  }
}

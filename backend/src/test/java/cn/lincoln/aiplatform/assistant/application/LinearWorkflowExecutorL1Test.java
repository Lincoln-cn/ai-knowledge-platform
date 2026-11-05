package cn.lincoln.aiplatform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lincoln.aiplatform.support.PostgresImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 判据 4 的 L1 检查点（真库）：每节点执行记录与业务同事务。
 *
 * <p>★ 判据：故意让某节点抛异常 ⇒ 该次执行的节点记录<strong>不得</strong>显示成功。用 {@code @Transactional} +
 * 查记录表证明；反证是「全成功时每节点各留一条」，证明记录确实会写、L1 的 0 才有意义。
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "app.security.captcha.enabled=false")
@DisplayName("判据4 · L1 检查点：每节点执行记录与业务同事务")
class LinearWorkflowExecutorL1Test {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = PostgresImage.container();

  @Autowired LinearWorkflowExecutor executor;
  @Autowired JdbcTemplate jdbc;

  /** 4 个假节点执行器：AI_CHAT 故意失败，其余成功。 */
  @TestConfiguration
  static class StubExecutors {
    @Bean
    NodeExecutor startStub() {
      return new Stub("START_NODE", false);
    }

    @Bean
    NodeExecutor searchStub() {
      return new Stub("SEARCH_DATASET_NODE", false);
    }

    @Bean
    NodeExecutor aiChatStub() {
      return new Stub("AI_CHAT_NODE", true);
    }

    @Bean
    NodeExecutor replyStub() {
      return new Stub("REPLY_NODE", false);
    }
  }

  static final class Stub implements NodeExecutor {
    private final String type;
    private final boolean fail;

    Stub(String type, boolean fail) {
      this.type = type;
      this.fail = fail;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public void execute(WorkflowNodeContext ctx) throws Exception {
      if (fail) {
        throw new IllegalStateException("AI_CHAT 节点故意失败（L1 判据）");
      }
    }
  }

  private static long succeeded(UUID executionId, JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "select count(*) from as_workflow_node_execution"
            + " where execution_id = ? and status = 'SUCCEEDED'",
        Long.class,
        executionId);
  }

  @Test
  @DisplayName("L1：某节点抛异常 ⇒ 该次执行的节点记录不得显示成功（SUCCEEDED 计数为 0）")
  void aFailingNodeLeavesNoSucceededRecords() {
    UUID executionId = UUID.randomUUID();
    WorkflowCompiler.ExecutionPlan plan =
        new WorkflowCompiler.ExecutionPlan(
            List.of(
                new WorkflowCompiler.PlanStep("s", "START_NODE", 0, null),
                new WorkflowCompiler.PlanStep("d", "SEARCH_DATASET_NODE", 1, null),
                new WorkflowCompiler.PlanStep("a", "AI_CHAT_NODE", 2, null),
                new WorkflowCompiler.PlanStep("r", "REPLY_NODE", 3, null)),
            List.of());

    assertThatThrownBy(() -> executor.execute(executionId, plan, "in"))
        .as("AI_CHAT 节点抛异常，整个执行失败")
        .isInstanceOf(IllegalStateException.class);

    assertThat(succeeded(executionId, jdbc))
        .as("L1：节点执行与记录同事务 ⇒ 失败那跑不得留下任何 SUCCEEDED 节点记录")
        .isZero();
  }

  @Test
  @DisplayName("反证：全成功时每节点各留一条 SUCCEEDED（证明记录确实会写，L1 的 0 才有意义）")
  void aSuccessfulRunRecordsEveryNode() {
    UUID executionId = UUID.randomUUID();
    WorkflowCompiler.ExecutionPlan plan =
        new WorkflowCompiler.ExecutionPlan(
            List.of(
                new WorkflowCompiler.PlanStep("s", "START_NODE", 0, null),
                new WorkflowCompiler.PlanStep("r", "REPLY_NODE", 1, null)),
            List.of());

    executor.execute(executionId, plan, "in");

    assertThat(succeeded(executionId, jdbc)).as("反证：全成功那跑每节点各一条 SUCCEEDED").isEqualTo(2);
  }
}

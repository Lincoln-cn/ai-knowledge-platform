package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.PromptEvalAnswer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 答案读口。一次运行最多 30 题 × 2 侧 = 60 行，一次全取回来即可。 */
public interface JpaPromptEvalAnswerRepository extends JpaRepository<PromptEvalAnswer, UUID> {

  List<PromptEvalAnswer> findByRunId(UUID runId);

  Optional<PromptEvalAnswer> findByRunIdAndItemIdAndSide(
      UUID runId, UUID itemId, cn.lincoln.aiplatform.assistant.domain.EvalSide side);
}

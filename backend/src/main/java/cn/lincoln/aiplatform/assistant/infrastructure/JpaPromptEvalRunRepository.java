package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.PromptEvalRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 运行的读口。按开始时间倒序：列表里最上面那条是"最近一次"。 */
public interface JpaPromptEvalRunRepository extends JpaRepository<PromptEvalRun, UUID> {

  List<PromptEvalRun> findBySetIdOrderByStartedAtDesc(UUID setId);
}

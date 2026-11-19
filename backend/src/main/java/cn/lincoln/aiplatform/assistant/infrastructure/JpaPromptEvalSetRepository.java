package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.PromptEvalSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 评测集的读口。评测集数量很少（一条提示词几套），不需要分页。 */
public interface JpaPromptEvalSetRepository extends JpaRepository<PromptEvalSet, UUID> {

  List<PromptEvalSet> findByPromptConfigIdOrderByCreatedAtDesc(UUID promptConfigId);
}

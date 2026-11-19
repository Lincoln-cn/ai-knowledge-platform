package cn.lincoln.aiplatform.assistant.infrastructure;

import cn.lincoln.aiplatform.assistant.domain.PromptConfigVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 提示词历史版本（只追加）。 */
public interface JpaPromptConfigVersionRepository extends JpaRepository<PromptConfigVersion, UUID> {

  List<PromptConfigVersion> findByConfigIdOrderByVersionNoDesc(UUID configId);

  Optional<PromptConfigVersion> findByConfigIdAndVersionNo(UUID configId, int versionNo);

  /**
   * 当前最大版本号；没有历史时返回 0。
   *
   * <p>★ 用 coalesce 而不是让调用方判 null：调用方只需要"下一个号是几"，多一个 null 分支就多一处能忘掉的地方。
   */
  @Query(
      "select coalesce(max(v.versionNo), 0) from PromptConfigVersion v where v.configId ="
          + " :configId")
  int maxVersionNo(@Param("configId") UUID configId);
}

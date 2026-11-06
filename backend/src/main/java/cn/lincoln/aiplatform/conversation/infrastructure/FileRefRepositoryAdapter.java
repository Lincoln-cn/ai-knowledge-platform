package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.FileRef;
import cn.lincoln.aiplatform.conversation.domain.FileRefRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link FileRefRepository} 的 JPA 实现。 */
@Component
public class FileRefRepositoryAdapter implements FileRefRepository {

  private final JpaFileRefRepository jpa;

  public FileRefRepositoryAdapter(JpaFileRefRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public FileRef save(FileRef file) {
    return jpa.save(file);
  }

  /** ★ 直接透传 {@code findById}：不做归属过滤 —— 归属判定在调用方（{@code AttachmentRegistrar}），那里才拿得到"我是谁"。 */
  @Override
  public Optional<FileRef> findById(UUID id) {
    return jpa.findById(id);
  }

  @Override
  public void delete(FileRef file) {
    jpa.delete(file);
  }
}

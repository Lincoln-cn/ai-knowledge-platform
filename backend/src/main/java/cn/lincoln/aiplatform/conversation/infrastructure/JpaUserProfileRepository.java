package cn.lincoln.aiplatform.conversation.infrastructure;

import cn.lincoln.aiplatform.conversation.domain.UserProfile;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户画像的仓储（{@code #296}，表 {@code cv_user_profile}）。
 *
 * <p>★ 只有"按 id 取"与"存"，没有查询：画像一人一行，任何筛选都该在 Java 里做， 加查询方法只会让"谁在读画像"这件事变得不透明。
 */
public interface JpaUserProfileRepository extends JpaRepository<UserProfile, UUID> {}

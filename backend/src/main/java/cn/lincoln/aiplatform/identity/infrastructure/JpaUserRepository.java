package cn.lincoln.aiplatform.identity.infrastructure;

import cn.lincoln.aiplatform.identity.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data 的用户仓储。
 *
 * <p>★ 它是**纯 Spring Data 接口**，不再同时继承 {@code domain} 的 {@code UserRepository} —— 那个"双继承"写法在只需要
 * {@code findByUsername} / {@code save} 时很省事， 但一旦涉及分页就必须出现 {@code Pageable}（框架类型），而领域接口不该认识它。
 * 所以从这里起改用 {@link UserRepositoryAdapter} 做适配：domain 接口守住朴素签名， 框架类型只停在 infrastructure。
 *
 * <h3>★ 列表查询为什么改用 {@code Specification}（第 3 周第二段 · 联调暴露的真 bug）</h3>
 *
 * <p>原先 {@code search}/{@code countSearch} 是 {@code @Query} HQL，谓词里写 {@code concat('%', :keyword,
 * '%')}。 问题在于 {@code :keyword} 在 HQL 里<strong>没有任何类型信息</strong>：冷启动后第一次若 keyword 为 null， Hibernate
 * 推不出绑定类型 ⇒ 回退 {@code JAVA_OBJECT}（PG 按 bytea 发送）⇒ {@code lower(bytea)} 不存在 ⇒ 500。
 * 而且这个类型会<strong>缓存进查询计划</strong>，后续沿用 —— 于是"测试恰好先跑了一条带 keyword 的用例"就能把类型锁成
 * VARCHAR，让整类测试顺序偶然地绿，真环境冷启动首屏却必挂。
 *
 * <p>改成 {@link JpaSpecificationExecutor} 后：<strong>空条件就不拼谓词</strong>，从根上不存在"传 null 进查询"这件事；
 * 同时这也是设计文档指定的做法（{@code 08 §4.3.3}：「把范围拼进查询 → 官方 {@code Specification}，不是查完再筛」）， 第 5 周知识库列表要把
 * dataScope 拼进查询时形状一样。
 */
public interface JpaUserRepository
    extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

  Optional<User> findByUsernameAndDeletedAtIsNull(String username);

  /** 软删过的 id 查不到 —— 调用方据此返回 404。 */
  Optional<User> findByIdAndDeletedAtIsNull(UUID id);

  long countByRoleIdAndDeletedAtIsNull(UUID roleId);
}

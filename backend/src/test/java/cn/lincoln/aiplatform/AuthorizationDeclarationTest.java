package cn.lincoln.aiplatform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 写端点必须声明权限（{@code 08 §4.3.2}），并带一条金丝雀。
 *
 * <p>★ 它盯的是<strong>原项目最贵的那个病</strong>：新增功能忘配权限 ⇒ 功能隐形。 换用官方 {@code @PreAuthorize}
 * 之后，风险从"忘配权限"变成"<em>忘写注解</em>"—— 而后者同样不会崩、不会报错，只会让一个接口对所有登录用户开放。 这类问题不会有人报
 * bug（因为"能用"），只会某天被发现"普通用户居然能建知识库"。
 *
 * <p>★ 与 {@code ModuleBoundaryTest} 同构：同一条规则跑两个命名空间 —— 应用代码必须通过，{@code canaryfixture} 里的坏样本必须被抓到。
 */
class AuthorizationDeclarationTest {

  private static final JavaClasses APP_CLASSES =
      new ClassFileImporter().importPackages("cn.lincoln.aiplatform");

  private static final JavaClasses CANARY_CLASSES =
      new ClassFileImporter().importPackages("canaryfixture");

  /**
   * 白名单（{@code 08 §4.3.2} 允许的三类：{@code /auth/**}、{@code /public/**}、{@code /users/me/**}）。
   *
   * <p>目前写端点只落在 {@code AuthController}（登录 / 刷新 / 登出 —— 本人即可，不需要权限点）； {@code /users/me} 是
   * GET，不在本规则范围内。
   *
   * <p>⚠️ 若这个类被改名，白名单会失效 —— 那时它的写端点会被要求声明权限并<strong>构建失败</strong>。 失败方向是安全的（有人会被明确通知到），比"悄悄放开"好得多。
   */
  private static final Set<String> WHITELISTED_CONTROLLERS = Set.of("AuthController");

  @Test
  void 每个写端点都必须声明权限() {
    writeEndpointsMustDeclareAuthority("cn.lincoln.aiplatform")
        // ★ 允许"没有匹配项"：第 2 周的三个写端点（登录/刷新/登出）都在白名单里，
        //   所以这条规则**当前确实是空跑的**。这不是放宽要求 —— ArchUnit 默认把空跑当失败，
        //   本意是防止规则写错导致永远绿；而"规则到底有没有效"这件事，
        //   由下面那条金丝雀负责证明。第 3 周有了用户/角色 CRUD 之后它才会有真正的检查对象。
        .allowEmptyShould(true)
        .check(APP_CLASSES);
  }

  @Test
  void 金丝雀_漏声明权限的端点必须被抓到() {
    assertThatThrownBy(
            () -> writeEndpointsMustDeclareAuthority("canaryfixture").check(CANARY_CLASSES))
        .as("坏样本没被拦下 ⇒ 这条闸门已经失效，主代码的绿说明不了任何事")
        .isInstanceOf(AssertionError.class);
  }

  /**
   * 规则：写端点必须带 {@code @PreAuthorize}。
   *
   * @param basePackage 命名空间前缀。同一规则跑两个空间：应用代码必须过、样本必须不过。
   */
  private static ArchRule writeEndpointsMustDeclareAuthority(String basePackage) {
    DescribedPredicate<JavaMethod> needsDeclaration =
        new DescribedPredicate<>("是写端点、位于 " + basePackage + " 下、且不在白名单里") {
          @Override
          public boolean test(JavaMethod method) {
            if (!isWriteEndpoint(method)) {
              return false;
            }
            JavaClass owner = method.getOwner();
            return owner.isAnnotatedWith(RestController.class)
                && owner.getPackageName().startsWith(basePackage)
                && !WHITELISTED_CONTROLLERS.contains(owner.getSimpleName());
          }
        };

    return methods().that(needsDeclaration).should().beAnnotatedWith(PreAuthorize.class);
  }

  /**
   * 写端点 = {@code POST / PUT / PATCH / DELETE}。
   *
   * <p>★ {@code GET} 不在其中是有意的：查询类端点由<strong>数据范围</strong>（第 3 周）管 —— 用权限点逐一标注会把 {@code /users/me}
   * 这类"本人即可"的端点也逼着编一个权限点出来。
   */
  private static boolean isWriteEndpoint(JavaMethod method) {
    return method.isAnnotatedWith(PostMapping.class)
        || method.isAnnotatedWith(PutMapping.class)
        || method.isAnnotatedWith(PatchMapping.class)
        || method.isAnnotatedWith(DeleteMapping.class);
  }
}

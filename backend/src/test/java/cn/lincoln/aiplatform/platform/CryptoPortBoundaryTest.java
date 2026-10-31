package cn.lincoln.aiplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lincoln.aiplatform.platform.domain.CryptoPort;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 密码学原语只许从少数登记过的位置进入（{@code #74} 判据 1 与判据 3 金丝雀）。
 *
 * <h3>为什么需要这条断言</h3>
 *
 * <p>{@code #74} 的处置写了"端口存在且被消费"，但<strong>"端口存在"不等于"调用点走它"</strong>：把算法抽成接口很容易，之后任何一个新功能顺手写一句
 * {@code Cipher.getInstance("AES/GCM/NoPadding")} 就能绕开它，而编译、测试、界面全都不会有任何反应 —— 到真要上国密时，那一处就是没人知道的漏点。
 * 这类"抽象建好但可以被静默绕开"的缝，和"没有缝"在事故时表现完全一样。
 *
 * <h3>为什么用「集合相等」而不是「不得依赖」</h3>
 *
 * <p>白名单里有两个<b>是真的要被允许</b>的位置（理由见下面的常量注释）。如果写成"除 A 以外都不许"，
 * 白名单里的类以后不再用原语时不会有任何提示，那条豁免会永远留着；写成"集合必须等于白名单"， 则两个方向都会红 ——
 * <strong>多一个没登记的直接调用要红，登记过的消失了也要红</strong>（同 {@code check_states.py} 的"清单必须随修复收缩"）。
 *
 * <h3>金丝雀（判据 3）</h3>
 *
 * <p>第三条断言要求端口至少有一个实现：把调用点都改成 {@code CryptoPort} 之后<strong>把实现删了</strong>，静态上同样看不出来 ——
 * 留一条没有实现的缝，等于"看起来已经接好了"。
 */
class CryptoPortBoundaryTest {

  /** 主代码（不含测试）—— 用 {@code target/classes} 而不是包名，否则同包名的测试类会混进来。 */
  private static final JavaClasses MAIN_CLASSES =
      new ClassFileImporter().importPath(Path.of("target", "classes"));

  /**
   * 允许直接用 {@code javax.crypto} 的类。
   *
   * <ul>
   *   <li>{@code AesGcmCryptoPort} —— 端口实现，<b>算法只许在这里出现</b>
   *   <li>{@code SecurityConfig} —— ★ JWT 的 HMAC 密钥材料在这里生成。它属<b>签名侧</b>：{@code CryptoPort} 刻意不装
   *       {@code sign}/{@code verify}（见该接口的 javadoc），因为 JWT 签发与验签走官方 jose，换国密是"jose 能不能挂 SM2"的问题，
   *       而不是"我们的端口有没有方法"的问题
   * </ul>
   */
  private static final Set<String> JCA_ALLOWLIST =
      new TreeSet<>(List.of("AesGcmCryptoPort", "SecurityConfig"));

  /**
   * 允许直接用 {@code java.security.MessageDigest} 的类。
   *
   * <ul>
   *   <li>{@code AesGcmCryptoPort} —— 端口实现
   *   <li>{@code SearchText} —— ★ 它的 {@code contentHash}
   *       是<b>内容指纹</b>，不是安全摘要：换算法会让<b>全部历史分段哈希失效</b>、 触发整库重算（{@code
   *       SEG-I7}）。所以它<b>不能</b>跟着"密码算法合规"一起换 —— 两者要换的时机不同，混在一个端口里 等于让一次国密适配悄悄改变数据指纹
   * </ul>
   */
  private static final Set<String> DIGEST_ALLOWLIST =
      new TreeSet<>(List.of("AesGcmCryptoPort", "SearchText"));

  @Test
  void jcaCipherUsersMustBeExactlyAllowlisted() {
    Set<String> actual =
        classesDependingOn(MAIN_CLASSES, c -> c.getPackageName().startsWith("javax.crypto"));

    assertThat(actual)
        .as(
            "对称加解密只许从 CryptoPort 进入 —— 业务模块直接 import javax.crypto 就等于给国密改造留了一个"
                + "编译、测试、界面都不会报错的漏点（#74 判据 1）")
        .isEqualTo(JCA_ALLOWLIST);
  }

  @Test
  void messageDigestUsersMustBeExactlyAllowlisted() {
    Set<String> actual =
        classesDependingOn(
            MAIN_CLASSES, c -> c.getFullName().equals("java.security.MessageDigest"));

    assertThat(actual)
        .as("摘要只在端口实现与「内容指纹」处出现；新增的直接调用先来登记，并写清它属于哪一侧（安全摘要还是内容指纹）")
        .isEqualTo(DIGEST_ALLOWLIST);
  }

  @Test
  void portMustHaveAtLeastOneImplementation() {
    List<String> implementations =
        MAIN_CLASSES.stream()
            .filter(javaClass -> !javaClass.isInterface())
            .filter(
                javaClass ->
                    javaClass.getAllRawInterfaces().stream()
                        .anyMatch(type -> type.getName().equals(CryptoPort.class.getName())))
            .map(JavaClass::getSimpleName)
            .toList();

    assertThat(implementations)
        .as("CryptoPort 一个实现都没有 ⇒ 留的是空缝：调用点全部编译通过，启动时才炸（#74 判据 3 金丝雀）")
        .isNotEmpty();
  }

  /** 收集「直接依赖了满足 {@code target} 的类」的全部类名。 */
  private static Set<String> classesDependingOn(JavaClasses classes, Predicate<JavaClass> target) {
    return classes.stream()
        .filter(
            javaClass ->
                javaClass.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> target.test(dependency.getTargetClass())))
        .map(JavaClass::getSimpleName)
        .collect(Collectors.toCollection(TreeSet::new));
  }
}

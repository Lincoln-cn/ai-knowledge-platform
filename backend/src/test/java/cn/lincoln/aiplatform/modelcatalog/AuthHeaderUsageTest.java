package cn.lincoln.aiplatform.modelcatalog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 认证头的**唯一构造点**必须一直是 {@code AuthHeader}（{@code #221} 判据③ 的后半句）。
 *
 * <p>★ 为什么需要一条静态断言，而不是把话说在评论里：{@code #221} 的处置是"一个共用校验点，两条路径都过"， 而"两条路径都过"在代码里只表现为两处各有一行 {@code
 * AuthHeader.apply(...)}。这种"看起来在某处、 实际在另一处"的形状（同 {@code #218}）只能靠扫调用点来钉——一旦有人图省事在某条新路径里重新内联 {@code
 * builder.header(style.headerName(), style.prefix() + key)}，校验就少一半，而所有功能用例仍然是绿的。
 *
 * <p>★ 断言选的是 {@code AuthStyle.headerName()} 的**调用者集合**，不是"含 header( 字样的文件数"： 后者会把 {@code
 * Content-Type} 这类固定头也扫进来（{@code UpstreamGateway} 与 {@code NativeSpringAiChatModel}
 * 各有一处），只能维护一张豁免表；前者恰好就是"谁在拼认证头"， 没有一条需要豁免。
 */
class AuthHeaderUsageTest {

  private static final String AUTH_HEADER =
      "cn.lincoln.aiplatform.modelcatalog.infrastructure.AuthHeader";

  private static final String AUTH_STYLE = "cn.lincoln.aiplatform.modelcatalog.domain.AuthStyle";

  @Test
  void onlyAuthHeaderMayReadTheHeaderName() {
    JavaClasses classes = new ClassFileImporter().importPackages("cn.lincoln.aiplatform");

    ArchRule rule =
        noClasses()
            .that()
            .areNotAssignableTo(AUTH_HEADER)
            .should()
            .callMethod(AUTH_STYLE, "headerName")
            .because(
                "认证头的拼装必须走 AuthHeader.apply(...)：（1）它是密钥字符集的唯一校验点，绕开它就等于让"
                    + "非法密钥重新变成 500；（2）它保证头名与前缀成对出现，两条出站路径不会各自漂移。");

    rule.check(classes);
  }

  /**
   * 金丝雀：上面那条规则必须**真的看得见调用**。
   *
   * <p>若把 {@code callMethod} 的类名或方法名写错，ArchUnit 不会报错、只会永远绿——这在本项目是有先例的坏味 （{@code #135}：靠 {@code
   * ImportOption} 收窄过的闸门无法自证）。所以这里用一条断言把"它至少能看见一个 已知的调用者"固定住：{@code AuthHeader} 自己调 {@code
   * headerName()} 这一条必须被扫到。
   *
   * <p>注意：这条断言断的是"{@code headerName()} 的调用者非空且包含 {@code AuthHeader}"，与上面的规则互补——
   * 上面管"别人不许调"，这条管"这套扫描确实能看见调用"。
   */
  @Test
  void theScanActuallySeesCallSites() {
    JavaClasses classes = new ClassFileImporter().importPackages("cn.lincoln.aiplatform");

    List<String> callers =
        classes.stream()
            .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
            .filter(call -> call.getTarget().getName().equals("headerName"))
            .filter(call -> call.getTarget().getOwner().getName().equals(AUTH_STYLE))
            .map(call -> call.getOriginOwner().getName())
            .distinct()
            .toList();

    assertThat(callers).as("headerName() 的调用者集合（扫描自身是否有效）").containsExactly(AUTH_HEADER);
  }
}

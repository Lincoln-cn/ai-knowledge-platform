package canaryfixture.conversation.internal;

/**
 * 金丝雀样本的"上游内部类"。
 *
 * <p>★ <strong>这个类不是产品代码</strong>。它只存在于测试树里，作用是被 {@link canaryfixture.retrieval.IllegalDependent}
 * 越界引用，从而让 {@code ModuleBoundaryTest} 能断言"<em>闸门确实会抓到这条边</em>"。 （★ 2026-10-14 订正：此处原写 {@code
 * DependencyDirectionTest}，那个类**不存在** —— 见 `#118` 的同类记账。）
 *
 * <p>放在 {@code canaryfixture} 顶层包、而不是 {@code cn.lincoln.aiplatform} 下面， 是刻意的：Spring Modulith
 * 会扫描应用根包下的所有类（<em>包括测试树里的类</em>）， 若把样本放进应用包，它会凭空多出一个"模块"，把 {@code ApplicationModules.verify()}
 * 的结果污染掉。
 */
public class ConversationInternal {}

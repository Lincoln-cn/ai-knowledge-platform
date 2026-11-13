package cn.lincoln.aiplatform.platform.assistant;

import java.util.List;
import java.util.Set;

/**
 * 流程里的「函数」能力：把一段文本按一个具名函数变换成另一段文本。
 *
 * <h3>为什么这里<strong>不</strong>执行用户脚本</h3>
 *
 * <p>节点在目录里叫「函数库」/「函数」，很容易被读成「能写代码」。但执行用户上传的代码是 一条独立的安全边界 —— 它需要进程/容器级隔离、超时能杀、资源上限、以及"跑的是什么代码"的审计，
 * 排期在 {@code ROADMAP §11.27.3} 的 <strong>0.9.0</strong>，判据是"沙箱能起、超时能杀"。
 *
 * <p>在那之前做一个"看起来能跑代码、实际跑不了"的节点，比不做更糟：用户写下脚本、保存成功、 运行时静默什么都不做，这是把 {@code silent-failure}
 * 装进产品。所以本端口只暴露 <strong>平台内置函数</strong>，名字由 {@link #functionNames()} 给出。
 *
 * <h3>参数怎么来</h3>
 *
 * <p>端口收的是**已经解析好的字符串** —— 流程变量的取值发生在执行器里， 因为"哪个变量"是流程层的事，端口不该认识 {@code FlowState}。这样端口在测试里可以完全离线。
 *
 * <h3>失败</h3>
 *
 * <p>参数个数不对、类型不对、JSON 解析不了，一律抛 {@link IllegalArgumentException} 并带可读原因；
 * 执行器把它变成节点失败记录。<strong>不返回空串</strong> —— 空串会被下游当成"函数算出来是空的"， 而真因是"这一格配错了"。
 */
public interface FunctionInvokePort {

  /** 内置函数名（小写，驼峰不参与）。面板据此出下拉，校验据此判"这个名字存不存在"。 */
  Set<String> functionNames();

  /**
   * 执行一个内置函数。
   *
   * @param functionName {@link #functionNames()} 里的名字
   * @param args 已解析的参数值，个数与顺序按函数自己的约定
   * @return 变换结果（可能是空串 —— 那是一个合法结果，例如 {@code trim} 一个全空白的串）
   * @throws IllegalArgumentException 函数不存在 / 参数个数不对 / 参数内容非法（带可读原因）
   */
  String invoke(String functionName, List<String> args);
}

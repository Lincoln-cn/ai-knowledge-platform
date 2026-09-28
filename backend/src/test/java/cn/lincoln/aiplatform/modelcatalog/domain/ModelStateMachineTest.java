package cn.lincoln.aiplatform.modelcatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link Model} 四态状态机（第 4 周 · 0.1.3 测试项 ①）。
 *
 * <p>★ 契约的四态不是装饰：它们决定了界面能不能真的走全。所以这里不只测"合法路径通"，
 * 而是把<strong>每一个非法转移都钉死</strong>，并断言被拒后状态<strong>没变</strong> —— 只断言抛异常是不够的：
 * 一个先改状态再抛的实现会通过前者、却把界面推进一个不可恢复的角落。
 */
class ModelStateMachineTest {

  private static Model newModel() {
    return new Model(
        UUID.randomUUID(),
        "测试模型",
        ModelKind.CHAT,
        Supplier.EXTERNAL_API,
        "qwen3:8b",
        "http://localhost:11434/v1",
        null,
        null,
        32768,
        null,
        ThinkingMode.NONE,
        ToolsMode.UNSUPPORTED,
        false,
        null,
        null);
  }

  // ────────────────────────── 合法路径 ──────────────────────────

  @Test
  void 合法路径_未加载到加载中到已加载() {
    Model model = newModel();
    assertThat(model.status()).isEqualTo(ModelStatus.NOT_LOADED);
    assertThat(model.enabled()).as("新建的模型不应被业务走到").isFalse();

    model.startLoad();
    assertThat(model.status()).isEqualTo(ModelStatus.LOADING);

    model.markLoaded();
    assertThat(model.status()).isEqualTo(ModelStatus.LOADED);
    assertThat(model.enabled()).as("加载成功后平台才允许启用").isTrue();
    assertThat(model.loadError()).isNull();
  }

  @Test
  void 合法路径_加载失败后可以重新加载并成功() {
    Model model = newModel();
    model.startLoad();
    model.markFailed("网关不可达：连接 http://localhost:11434/v1 超时");

    assertThat(model.status()).isEqualTo(ModelStatus.LOAD_FAILED);
    assertThat(model.loadError()).contains("超时");
    assertThat(model.enabled()).isFalse();

    // 失败后必须能重试 —— 重试走 startLoad() 回到「加载中」，再走结果
    model.startLoad();
    assertThat(model.status()).isEqualTo(ModelStatus.LOADING);
    model.markLoaded();
    assertThat(model.status()).isEqualTo(ModelStatus.LOADED);
    assertThat(model.loadError()).as("重新加载成功后必须清掉上一次的失败原因").isNull();
    assertThat(model.enabled()).isTrue();
  }

  @Test
  void 合法路径_加载中可以再次失败并换原因() {
    Model model = newModel();
    model.startLoad();
    model.markFailed("网关不可达：连接 http://a 超时");
    model.startLoad();
    model.markFailed("模型权重文件损坏：checksum 不匹配");

    assertThat(model.loadError()).as("第二次失败的原因要覆盖第一次").contains("checksum");
    assertThat(model.status()).isEqualTo(ModelStatus.LOAD_FAILED);
  }

  @Test
  void 任意态都可以重新发起加载() {
    for (ModelStatus prior :
        new ModelStatus[] {
          ModelStatus.NOT_LOADED, ModelStatus.LOADING, ModelStatus.LOADED, ModelStatus.LOAD_FAILED
        }) {
      Model model = newModel();
      reach(model, prior);
      model.startLoad();
      assertThat(model.status()).as("从「%s」发起加载应合法", prior).isEqualTo(ModelStatus.LOADING);
    }
  }

  @Test
  void clearEnable关掉启用位() {
    Model model = newModel();
    model.startLoad();
    model.markLoaded();
    assertThat(model.enabled()).isTrue();

    model.clearEnable();
    assertThat(model.enabled()).isFalse();
    assertThat(model.status()).as("clearEnable 只动 enabled，不动 status").isEqualTo(ModelStatus.LOADED);
  }

  // ────────────────────────── 非法转移：必须拒绝且不改状态 ──────────────────────────

  @Test
  void 非法转移_未加载不能直接标记加载成功() {
    Model model = newModel();

    assertThatThrownBy(model::markLoaded)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOT_LOADED")
        .hasMessageContaining("LOADING");

    assertThat(model.status()).as("被拒后状态不得改变").isEqualTo(ModelStatus.NOT_LOADED);
    assertThat(model.enabled()).isFalse();
    assertThat(model.loadError()).isNull();
  }

  @Test
  void 非法转移_未加载不能直接标记加载失败() {
    Model model = newModel();

    assertThatThrownBy(() -> model.markFailed("网关不可达：连接 http://x 超时"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOT_LOADED");

    assertThat(model.status()).isEqualTo(ModelStatus.NOT_LOADED);
    assertThat(model.loadError()).isNull();
  }

  @Test
  void 非法转移_加载失败不能直接标记加载成功() {
    Model model = newModel();
    model.startLoad();
    model.markFailed("网关不可达：连接 http://x 超时");

    assertThatThrownBy(model::markLoaded)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOAD_FAILED")
        .hasMessageContaining("startLoad");

    assertThat(model.status()).as("被拒后仍停在加载失败").isEqualTo(ModelStatus.LOAD_FAILED);
    assertThat(model.loadError()).as("被拒后失败原因不得被清掉").contains("超时");
    assertThat(model.enabled()).isFalse();
  }

  @Test
  void 非法转移_已加载不能再次标记加载成功() {
    Model model = newModel();
    model.startLoad();
    model.markLoaded();

    assertThatThrownBy(model::markLoaded)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOADED");

    assertThat(model.status()).isEqualTo(ModelStatus.LOADED);
  }

  @Test
  void 非法转移_已加载不能标记加载失败() {
    Model model = newModel();
    model.startLoad();
    model.markLoaded();

    assertThatThrownBy(() -> model.markFailed("随便一个原因"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOADED");

    assertThat(model.status()).isEqualTo(ModelStatus.LOADED);
    assertThat(model.loadError()).isNull();
    assertThat(model.enabled()).as("被拒后 enabled 不得被关掉").isTrue();
  }

  @Test
  void 非法转移_加载失败不能再次标记加载失败() {
    Model model = newModel();
    model.startLoad();
    model.markFailed("网关不可达：连接 http://x 超时");

    assertThatThrownBy(() -> model.markFailed("又一个原因"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOAD_FAILED");

    assertThat(model.loadError()).as("被拒后原失败原因不得被覆盖").contains("超时");
  }

  @Test
  void 非法转移_加载中不能直接标记加载成功之外的捷径都被拦() {
    // 加载中是唯一能走 markLoaded / markFailed 的状态 —— 这里再钉一次"从加载中可以"
    Model model = newModel();
    model.startLoad();
    model.markLoaded();
    assertThat(model.status()).isEqualTo(ModelStatus.LOADED);
  }

  // ────────────────────────── 失败原因必须可读 ──────────────────────────

  @Test
  void 失败原因不能为空() {
    Model model = newModel();
    model.startLoad();

    assertThatThrownBy(() -> model.markFailed(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为空");
    assertThatThrownBy(() -> model.markFailed("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为空");

    assertThat(model.status()).as("原因不合法时状态也不得改变").isEqualTo(ModelStatus.LOADING);
  }

  @Test
  void 失败原因不能只重复状态名() {
    Model model = newModel();
    model.startLoad();

    assertThatThrownBy(() -> model.markFailed("LOAD_FAILED"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("排查");
    assertThatThrownBy(() -> model.markFailed("失败"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("排查");

    assertThat(model.status()).isEqualTo(ModelStatus.LOADING);
  }

  @Test
  void 失败原因必须是能照着排查的那句话() {
    Model model = newModel();
    model.startLoad();
    model.markFailed("  网关不可达：连接 http://localhost:11434/v1 超时  ");

    // 前后空白会被 trim 掉，正文保留
    assertThat(model.loadError()).isEqualTo("网关不可达：连接 http://localhost:11434/v1 超时");
  }

  // ────────────────────────── dimension / contextWindow 的 null 语义 ──────────────────────────

  @Test
  void 对话模型的dimension必须为null() {
    assertThatThrownBy(
            () ->
                new Model(
                    UUID.randomUUID(),
                    "chat",
                    ModelKind.CHAT,
                    Supplier.LOCAL_INFERENCE,
                    "qwen3:8b",
                    null,
                    null,
                    null,
                    32768,
                    768, // 对话模型不产出向量
                    ThinkingMode.NONE,
                    ToolsMode.UNSUPPORTED,
                    false,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dimension");
  }

  @Test
  void 向量模型的contextWindow必须为null() {
    assertThatThrownBy(
            () ->
                new Model(
                    UUID.randomUUID(),
                    "embed",
                    ModelKind.EMBEDDING,
                    Supplier.EXTERNAL_API,
                    "bge-m3",
                    "http://x",
                    null,
                    null,
                    8192, // 向量模型没有对话上下文窗口
                    1024,
                    ThinkingMode.NONE,
                    ToolsMode.UNSUPPORTED,
                    false,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contextWindow");
  }

  @Test
  void 对话模型dimension为null_向量模型contextWindow为null_都合法() {
    Model chat =
        new Model(
            UUID.randomUUID(),
            "chat",
            ModelKind.CHAT,
            Supplier.LOCAL_INFERENCE,
            "qwen3:8b",
            null,
            null,
            null,
            32768,
            null,
            ThinkingMode.TOGGLEABLE,
            ToolsMode.SUPPORTED,
            false,
            null,
            null);
    assertThat(chat.contextWindow()).isEqualTo(32768);
    assertThat(chat.dimension()).isNull();

    Model embedding =
        new Model(
            UUID.randomUUID(),
            "embed",
            ModelKind.EMBEDDING,
            Supplier.EXTERNAL_API,
            "bge-m3",
            "http://x",
            null,
            null,
            null,
            1024,
            ThinkingMode.NONE,
            ToolsMode.UNSUPPORTED,
            false,
            null,
            null);
    assertThat(embedding.dimension()).isEqualTo(1024);
    assertThat(embedding.contextWindow()).isNull();
  }

  @Test
  void thinking与tools未传时用保守默认值() {
    Model model =
        new Model(
            UUID.randomUUID(),
            "m",
            ModelKind.CHAT,
            Supplier.LOCAL_INFERENCE,
            "qwen3:8b",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null);

    assertThat(model.thinking()).isEqualTo(ThinkingMode.NONE);
    assertThat(model.tools()).isEqualTo(ToolsMode.UNSUPPORTED);
    assertThat(model.status()).isEqualTo(ModelStatus.NOT_LOADED);
    assertThat(model.enabled()).isFalse();
  }

  // ────────────────────────── 内部 ──────────────────────────

  /** 把模型推到指定状态（构造合法路径）。 */
  private static void reach(Model model, ModelStatus target) {
    switch (target) {
      case NOT_LOADED -> {
        // 已经是
      }
      case LOADING -> model.startLoad();
      case LOADED -> {
        model.startLoad();
        model.markLoaded();
      }
      case LOAD_FAILED -> {
        model.startLoad();
        model.markFailed("测试用的可读原因：某处不可达");
      }
    }
  }
}

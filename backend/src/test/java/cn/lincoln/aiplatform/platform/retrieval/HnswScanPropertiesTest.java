package cn.lincoln.aiplatform.platform.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link HnswScanProperties} 的取值校验。
 *
 * <p>★ 这些断言看着琐碎，但它们守的是一条会拼进 SQL 的字符串：{@code SET LOCAL hnsw.iterative_scan = ...}
 * 用不了占位符，所以"配置里能写什么"必须由白名单决定，而不能由"配置文件没人改"这个假设决定。
 */
class HnswScanPropertiesTest {

  @Test
  void defaultsWhenNothingIsConfigured() {
    HnswScanProperties p = new HnswScanProperties(null, null);
    assertEquals(40, p.efSearch(), "没配时应当落到 pgvector 的默认 40");
    assertEquals("strict_order", p.iterativeScan(), "没配时应当沿用原来的写死值，不改变已有部署行为");
  }

  @Test
  void blankScanModeFallsBackToStrictOrder() {
    assertEquals("strict_order", new HnswScanProperties(64, "  ").iterativeScan());
  }

  @Test
  void keepsConfiguredValues() {
    HnswScanProperties p = new HnswScanProperties(100, "relaxed_order");
    assertEquals(100, p.efSearch());
    assertEquals("relaxed_order", p.iterativeScan());
  }

  @Test
  void rejectsUnknownScanMode() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> new HnswScanProperties(40, "fastest"));
    assertTrue(e.getMessage().contains("iterative-scan"), "错误消息要说出是哪个键，否则用户只能靠猜：" + e.getMessage());
  }

  @Test
  void rejectsOutOfRangeEfSearch() {
    assertThrows(IllegalArgumentException.class, () -> new HnswScanProperties(0, "strict_order"));
    assertThrows(
        IllegalArgumentException.class, () -> new HnswScanProperties(10001, "strict_order"));
  }
}

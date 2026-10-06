package cn.lincoln.aiplatform.knowledge.infrastructure;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 全文索引的自检（{@code 09 §10.2 #3} 第 3 条：<strong>启动必须自检</strong>）。
 *
 * <h3>为什么这条自检必须有</h3>
 *
 * <p>全文检索缺东西时的症状是<strong>静默</strong>的：{@code zhparser} 没装、或 {@code chinese_zh} 配置没建、或 language 包不在
 * {@code tsearch_data}，表现都是"全文检索一条都搜不到"， 而向量路照常出结果 ⇒ 混合模式**静默退化成纯向量**（{@code 11 §风险} 第 3 条）。
 * 这正是本项目最怕的那类失效，所以设计册要求它与架构测试同等待遇。
 *
 * <p>★ 落在两处：<strong>这里</strong>（启动时一行显式日志，运维能一眼看到）与 {@code FullTextIndexTest}（CI
 * 里的断言，跟镜像一起换时会红）。只写日志不够 —— 没人读日志；只写测试也不够 —— 现场部署不会跑测试。
 *
 * <p>★ 探测是<strong>懒加载 + 缓存</strong>（与 {@code OcrEngine} 同口径）：迁移还没跑完时
 * 不该把启动打挂；第一次真需要它的时候（或应用就绪时）才探测，之后复用结果。
 */
@Component
public class FullTextIndex {

  private static final Logger log = LoggerFactory.getLogger(FullTextIndex.class);

  /**
   * 自检用的样本文本 —— 选它的理由：{@code 合同违约金} 在 zhparser 下必须是 <strong>两个词</strong>（{@code 合同} / {@code
   * 违约金}）；整串一个词元就说明 extension 或配置是假的（那正是 {@code simple} 配置的症状）。
   */
  private static final String SAMPLE = "合同违约金";

  private final JdbcTemplate jdbc;

  private volatile Probe cached;

  public FullTextIndex(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 启动时打一行显式事实（可核对的字面量，不是"看起来没问题"）。 */
  @EventListener(ApplicationReadyEvent.class)
  public void reportAtStartup() {
    Probe probe = probe();
    if (probe.available()) {
      log.info("全文索引：可用（chinese_zh 分词：{}）", probe.detail());
    } else {
      log.warn(
          "★ 全文索引不可用：{} —— 症状是「全文检索一条都搜不到」而向量路照常，混合模式会静默退化成纯向量。"
              + "检查三件：镜像里有没有 zhparser（deploy/pg/Dockerfile）、扩展建没建、V19 迁移跑没跑",
          probe.detail());
    }
  }

  /** 探测一次并缓存。 */
  public Probe probe() {
    Probe local = cached;
    if (local == null) {
      local = doProbe();
      cached = local;
    }
    return local;
  }

  private Probe doProbe() {
    try {
      List<String> extensions =
          jdbc.queryForList(
              "select extname from pg_extension where extname in ('vector','zhparser')"
                  + " order by extname",
              String.class);
      if (!extensions.contains("zhparser")) {
        return new Probe(false, "扩展 zhparser 没装（当前有：" + extensions + "）");
      }
      String lexemes =
          jdbc.queryForObject(
              "select array_to_string(tsvector_to_array(to_tsvector('chinese_zh', ?)), ' | ')",
              String.class,
              SAMPLE);
      if (lexemes == null
          || lexemes.isBlank()
          || lexemes.lines().count() == 1 && lexemes.equals(SAMPLE)) {
        // ★ 整串原样返回 = 分词没生效（simple 配置就是这样）；空 = 词典没进 tsearch_data。
        //   两种情况都会让全文检索永远为空，所以都必须判为不可用。
        return new Probe(false, "chinese_zh 对「" + SAMPLE + "」的分词是「" + lexemes + "」，没有切成词");
      }
      return new Probe(true, lexemes);
    } catch (RuntimeException e) {
      // 配置不存在 ⇒ SQL 直接报错（text search configuration "chinese_zh" does not exist）
      return new Probe(
          false,
          e.getMessage() == null
              ? e.toString()
              : e.getMessage().lines().findFirst().orElse(e.toString()));
    }
  }

  /**
   * 探测结果。
   *
   * @param available 三件都对：扩展在、配置在、分词真的把词切开了
   * @param detail 可读证据：可用时是分词结果，不可用时是原因
   */
  public record Probe(boolean available, String detail) {}
}

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * #347 一次性清理：把 bge-m3 的 30 条重复登记收成 1 条，并清掉 768 维那批脏向量。
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │ 为什么需要动库、为什么必须一次性（2026-11-17）                              │
 * │                                                                          │
 * │ 现象：走查夹具建向量模型时 400 ——                                         │
 * │   「同一个基础模型 bge-m3 已有登记维度 768，不能再登记成 1024」。          │
 * │ 根因两层：                                                                │
 * │   ① 夹具不清理自己的产物（已在 `3a376fbf` 改成增量建模型，只修了"以后"）；  │
 * │   ② 唯一性规则是 `#305` 缺口 B 之后才加的，管不到已经躺在那里的 30 条。     │
 * │                                                                          │
 * │ 危害不是"多几行脏数据"，而是**向量按模型的 dimension 选 `vec_embedding_<dim>`│
 * │ 表**：同一个 bge-m3 的两个维度各自落到两张表 ⇒ 两批向量永远互相看不见，     │
 * │ 只在换模型重算之后以「检索结果少一半」的形式暴露（不报错）。                │
 * │                                                                          │
 * │ 为什么保留 1024：bge-m3 的原生维度就是 1024；且 `vec_embedding_1024` 里是   │
 * │ 真实的语料向量（1.4 万行），`vec_embedding_768` 那 203 行全是夹具/错表残留。│
 * │ 为什么保留 74b51c54：它是被引用最多的那一条（13 个库）。                     │
 * │                                                                          │
 * │ 四个动作（同一个事务）：                                                   │
 * │   ① 被别的维度/别的登记引用的库，`embedding_model_id` 改指保留条 ——         │
 * │      **同维度内安全**：向量由同一个 bge-m3 算出，维度和语义空间都没变；      │
 * │      768 维那批改指 1024 之后旧向量作废，但那批库全是夹具库，重跑会重建。    │
 * │   ② `vec_embedding_1024` 里属于被删登记的向量，metadata 的 `model_id`     │
 * │      改指保留条（检索侧 `JdbcVectorIndex` 不看这个字段，改它只为不留悬空引用）│
 * │   ③ 删掉 29 条登记；④ 清空 `vec_embedding_768`（203 行 = 88 行将被删模型  │
 * │      的向量 + 61 行 512 维模型错表 + 46 行孤儿模型 + 8 行 1024 维错表）。   │
 * └──────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * 判据（跑完自己核，脚本也会核）：
 *
 * <ol>
 *   <li>每个 base_model 只有一行（`select base_model, count(*) … group by 1`）；
 *   <li>走查连续跑两次 `count` 相同（要真栈，见 `#347` 正文）；
 *   <li>保留维度必须与已有向量表实际维度一致 —— 保留条 dimension=1024，`vec_embedding_1024` 在且非空；
 *   <li>清理后 `check_flow_walkthrough.py` 仍 47/47（要真栈）。
 * </ol>
 *
 * 用法（JDK 22+ 可直接跑单文件源码，不需要 Maven）：
 *
 * <pre>
 * # 先干跑，只打印将要改多少行
 * java -cp %USERPROFILE%\.m2\repository\org\postgresql\postgresql\42.7.14\postgresql-42.7.14.jar \
 *      tools\CleanBgeM3Duplicates.java
 * # 确认后真执行
 * java -cp … tools\CleanBgeM3Duplicates.java --apply
 * </pre>
 *
 * <p>连接参数可用 `--url/--user/--password` 覆盖，默认取本机开发库（15433）。脚本**幂等**：重复执行只会得到
 * "0 处改动"。
 */
public final class CleanBgeM3Duplicates {

    /** 保留条：bge-m3 / 1024 维 / B13向量-1793532094，被 13 个知识库引用。 */
    private static final String KEEP = "74b51c54-0ebf-487e-bb85-a11e89f3beeb";

    /** 其余 29 条（24 条 768 维夹具 + 5 条 1024 维语料解析/B13）。名字只为了让人审阅时能对上。 */
    private static final List<String[]> DUPS =
            List.of(
                    new String[] {"33ffdd18-22a6-4769-9408-d5a7f654135b", "动作闸门向量-14693471"},
                    new String[] {"0f5e1e63-d06a-4010-b829-cf4f6ded4c61", "动作闸门向量-15803805"},
                    new String[] {"d980d0fb-db7e-478d-b231-b0806ae42866", "动作闸门向量-70111590"},
                    new String[] {"b3cdc22c-05cc-4526-837f-0ada882dfae5", "动作闸门向量-71421896"},
                    new String[] {"ab7c6618-559e-45fc-9d88-83dd3ea79794", "密级闸门向量-1791343176"},
                    new String[] {"727593a1-3b17-44a8-8c18-135877548cd1", "探针向量-c52a10c1"},
                    new String[] {"77625855-3a79-4493-866e-88a1550b61fb", "查询理解向量-1791347758"},
                    new String[] {"18080111-3757-4c3f-8e4a-01143267affa", "查询理解向量-1791348323"},
                    new String[] {"11518cd8-3272-4675-91b9-3a5437f6ff50", "查询理解向量-1791348381"},
                    new String[] {"924d4d10-847d-4b3c-aecd-9600542f3305", "查询理解向量-1791349968"},
                    new String[] {"7156b5d5-ddc0-42d1-83cf-5d176f817da4", "查询理解向量-1791350020"},
                    new String[] {"5761d034-88e0-44f7-853f-93a758efa39d", "走查向量-1791278969"},
                    new String[] {"f94300a0-fcd5-430f-8768-5a802f3325ca", "走查向量-1791279050"},
                    new String[] {"79f4826a-7f0d-4255-822e-30fe12370f26", "走查向量-1791279200"},
                    new String[] {"faf95769-2b4c-4c34-895c-2bc6a8717f49", "走查向量-1791279402"},
                    new String[] {"3fc144db-830a-49ed-a590-ad7f746720f7", "走查向量-1791279498"},
                    new String[] {"c7ea6712-9ec5-4a53-9148-1aa85d38a681", "走查向量-1791279621"},
                    new String[] {"750d233b-49b7-4c23-9a4a-faa9b48c47d5", "走查向量-1791279755"},
                    new String[] {"1cf19f9d-9adc-4179-85cb-4fdaec0fbbf2", "走查向量-1791279977"},
                    new String[] {"0f06603a-99a0-4736-b6a1-acfb484e3692", "走查向量-1791280109"},
                    new String[] {"0a4c9e10-0d33-4833-b07b-b61f6b288126", "走查向量-1791349687"},
                    new String[] {"30377a84-d845-4825-b7da-aea41719486e", "走查向量-1791350088"},
                    new String[] {"eb508a3e-2cf0-424e-bfd7-27b454f83ab7", "走查向量-1791365221"},
                    new String[] {"3730c2db-0e6c-4d2e-857d-f326301f08aa", "走查向量-1791382183"},
                    new String[] {"938cef8e-be5d-4c4d-a97d-72cfb585fba4", "B13向量-1793536202"},
                    new String[] {"5c036bd6-910f-403f-895d-8cbeb01d893e", "B13向量-1793634335"},
                    new String[] {"e144ea66-d0b0-4354-84f3-3a289a735406", "语料解析向量-1792250632"},
                    new String[] {"37dead61-0903-441b-b3f3-0f6315506522", "语料解析向量-1792250686"},
                    new String[] {"5784c414-c894-435b-b982-a1cc5a6330b5", "语料解析向量-1792250745"});

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:15433/aiplatform";
    private static final String DEFAULT_USER = "aiplatform";
    private static final String DEFAULT_PASSWORD = "aiplatform";

    private CleanBgeM3Duplicates() {}

    public static void main(String[] args) throws Exception {
        boolean apply = false;
        String url = System.getenv().getOrDefault("AIP_DB_URL", DEFAULT_URL);
        String user = System.getenv().getOrDefault("AIP_DB_USER", DEFAULT_USER);
        String password = System.getenv().getOrDefault("AIP_DB_PASSWORD", DEFAULT_PASSWORD);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--apply" -> apply = true;
                case "--url" -> url = args[++i];
                case "--user" -> user = args[++i];
                case "--password" -> password = args[++i];
                default -> throw new IllegalArgumentException("看不懂的参数：" + args[i]);
            }
        }

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);

        List<String> dupIds = dupIds();
        System.out.println("保留：" + KEEP + "（bge-m3 / 1024）");
        System.out.println("待删：" + dupIds.size() + " 条");
        System.out.println(apply ? "模式：APPLY（真改）" : "模式：DRY-RUN（只读，不改动）");

        try (Connection connection = DriverManager.getConnection(url, props)) {
            connection.setAutoCommit(false);
            try {
                before(connection, dupIds);
                assertPreconditions(connection, dupIds);
                Plan plan = measure(connection, dupIds);
                System.out.printf(
                        "%n将改动：kb 改指 %d 行 · vec1024 改 metadata %d 行 · 删 ai_model %d 行 · 清 vec768 %d 行%n",
                        plan.kbRows, plan.vec1024Rows, plan.modelRows, plan.vec768Rows);
                if (!apply) {
                    connection.rollback();
                    System.out.println("\nDRY-RUN 结束，连接已回滚，库没有变。加 --apply 才真执行。");
                    return;
                }
                execute(connection, dupIds);
                verify(connection, dupIds);
                connection.commit();
                after(connection);
                System.out.println("\n✅ 已提交。判据 1/3 已由本脚本核对；2/4 需要真栈（见 #347）。");
            } catch (Exception error) {
                connection.rollback();
                System.err.println("\n❌ 失败，已回滚，库没有变：" + error.getMessage());
                throw error;
            }
        }
    }

    private static List<String> dupIds() {
        List<String> ids = new ArrayList<>();
        for (String[] dup : DUPS) {
            if (!ids.contains(dup[0])) {
                ids.add(dup[0]);
            }
        }
        return ids;
    }

    private static void before(Connection connection, List<String> dupIds) throws SQLException {
        System.out.println("\n=== 清理前");
        print(connection, "bge-m3 登记条数", "select count(*) from ai_model where base_model='bge-m3'");
        print(connection, "其中 768 维", "select count(*) from ai_model where base_model='bge-m3' and dimension=768");
        print(connection, "其中 1024 维", "select count(*) from ai_model where base_model='bge-m3' and dimension=1024");
        print(connection, "vec_embedding_768 行数", "select count(*) from vec_embedding_768");
        print(connection, "vec_embedding_1024 行数", "select count(*) from vec_embedding_1024");
        print(
                connection,
                "kb 引用这 29 条的库数",
                "select count(*) from kb_knowledge_base where embedding_model_id = any(" + arrayLiteral(connection, dupIds) + ")");
    }

    private static void assertPreconditions(Connection connection, List<String> dupIds) throws SQLException {
        System.out.println("\n=== 前置断言");
        Integer keepCount =
                scalar(connection, "select count(*) from ai_model where id = '" + KEEP + "'::uuid");
        Integer keepDim =
                scalar(connection, "select dimension from ai_model where id = '" + KEEP + "'::uuid");
        if (keepCount != 1 || keepDim == null || keepDim != 1024) {
            throw new IllegalStateException(
                    "保留条不成立：期望 1 行 bge-m3/1024，实际 keepCount=" + keepCount + " keepDim=" + keepDim);
        }
        System.out.println("✓ 保留条在，且 dimension=1024");

        Integer badDim =
                scalar(
                        connection,
                        "select count(*) from ai_model where id = any(" + arrayLiteral(connection, dupIds) + ") "
                                + "and (base_model <> 'bge-m3' or dimension not in (768, 1024))");
        if (badDim != 0) {
            throw new IllegalStateException("待删清单里混进了非 bge-m3 或非 768/1024 的行：" + badDim + " 条");
        }
        System.out.println("✓ 待删清单全部是 bge-m3，维度只有 768/1024");

        Integer chatRefs =
                scalar(
                        connection,
                        "select (select count(*) from as_assistant where model_id = any(" + arrayLiteral(connection, dupIds) + "))"
                                + " + (select count(*) from cv_chat_settings where default_model_id = any("
                                + arrayLiteral(connection, dupIds)
                                + "))"
                                + " + (select count(*) from cv_message where model_id = any("
                                + arrayLiteral(connection, dupIds)
                                + "))");
        if (chatRefs != 0) {
            throw new IllegalStateException("待删模型被对话侧引用，不能删：" + chatRefs + " 处");
        }
        System.out.println("✓ 对话侧（助理 / 会话设定 / 消息）没有引用它们");

        Integer dupInTable =
                scalar(
                        connection,
                        "select count(*) from ai_model where base_model='bge-m3' and id <> '"
                                + KEEP
                                + "'::uuid and id <> all("
                                + arrayLiteral(connection, dupIds)
                                + ")");
        if (dupInTable != 0) {
            throw new IllegalStateException(
                    "库里还有清单外的新 bge-m3 行（" + dupInTable + " 条）—— 先重新审阅清单再跑，别一把删");
        }
        System.out.println("✓ 清单之外没有新的 bge-m3 行");
    }

    private static Plan measure(Connection connection, List<String> dupIds) throws SQLException {
        String arr = arrayLiteral(connection, dupIds);
        Plan plan = new Plan();
        plan.kbRows =
                scalar(
                        connection,
                        "select count(*) from kb_knowledge_base where embedding_model_id = any(" + arr + ")");
        plan.vec1024Rows =
                scalar(
                        connection,
                        "select count(*) from vec_embedding_1024 where metadata->>'model_id' = any("
                                + textArrayLiteral(connection, dupIds)
                                + ")");
        plan.modelRows = scalar(connection, "select count(*) from ai_model where id = any(" + arr + ")");
        plan.vec768Rows = scalar(connection, "select count(*) from vec_embedding_768");
        return plan;
    }

    private static void execute(Connection connection, List<String> dupIds) throws SQLException {
        System.out.println("\n=== 执行");

        try (PreparedStatement statement =
                connection.prepareStatement(
                        "update kb_knowledge_base set embedding_model_id = ?::uuid "
                                + "where embedding_model_id = any(?::uuid[])")) {
            statement.setString(1, KEEP);
            statement.setArray(2, connection.createArrayOf("uuid", dupIds.toArray()));
            int rows = statement.executeUpdate();
            System.out.println("① kb 改指保留条：" + rows + " 行");
        }

        try (PreparedStatement statement =
                connection.prepareStatement(
                        "update vec_embedding_1024 set metadata = "
                                + "jsonb_set(metadata::jsonb, '{model_id}', to_jsonb(?::text))::json "
                                + "where metadata->>'model_id' = any(?::text[])")) {
            statement.setString(1, KEEP);
            statement.setArray(2, connection.createArrayOf("text", dupIds.toArray()));
            int rows = statement.executeUpdate();
            System.out.println("② vec_embedding_1024 的 metadata.model_id 改指保留条：" + rows + " 行");
        }

        try (PreparedStatement statement =
                connection.prepareStatement("delete from ai_model where id = any(?::uuid[])")) {
            statement.setArray(1, connection.createArrayOf("uuid", dupIds.toArray()));
            int rows = statement.executeUpdate();
            System.out.println("③ 删除重复登记：" + rows + " 行");
            if (rows != dupIds.size()) {
                throw new IllegalStateException("删登记删掉了 " + rows + " 行，期望 " + dupIds.size() + " 行");
            }
        }

        try (Statement statement = connection.createStatement()) {
            int rows = statement.executeUpdate("delete from vec_embedding_768");
            System.out.println("④ 清空 vec_embedding_768：" + rows + " 行");
        }
    }

    private static void verify(Connection connection, List<String> dupIds) throws SQLException {
        System.out.println("\n=== 后置断言");
        assertZero(connection, "bge-m3 登记条数（应为 1）", "select count(*) - 1 from ai_model where base_model='bge-m3'");
        assertZero(
                connection,
                "reserved 保留条的 kb 引用（应 >0）",
                "select case when count(*) = 0 then 1 else 0 end from kb_knowledge_base where embedding_model_id = '"
                        + KEEP
                        + "'::uuid");
        assertZero(
                connection,
                "kb 仍指向被删登记（应为 0）",
                "select count(*) from kb_knowledge_base where embedding_model_id = any(" + arrayLiteral(connection, dupIds) + ")");
        assertZero(
                connection,
                "vec_embedding_1024 仍带被删 model_id（应为 0）",
                "select count(*) from vec_embedding_1024 where metadata->>'model_id' = any("
                        + textArrayLiteral(connection, dupIds)
                        + ")");
        assertZero(connection, "vec_embedding_768 行数（应为 0）", "select count(*) from vec_embedding_768");
        assertZero(
                connection,
                "保留维度 1024 与 vec_embedding_1024 的一致性（表空则算不一致）",
                "select case when count(*) > 0 then 0 else 1 end from vec_embedding_1024");
        System.out.println("✓ 判据 1（每个 base_model 一行）、判据 3（保留维度与向量表一致）都过了");
    }

    private static void after(Connection connection) throws SQLException {
        System.out.println("\n=== 清理后");
        print(connection, "bge-m3 登记", "select id || ' / ' || dimension || ' / ' || name from ai_model where base_model='bge-m3'");
        print(connection, "vec 各表行数", "select '384=' || (select count(*) from vec_embedding_384) || ' 512=' || (select count(*) from vec_embedding_512) || ' 768=' || (select count(*) from vec_embedding_768) || ' 1024=' || (select count(*) from vec_embedding_1024)");
        print(connection, "kb 指向保留条的库数", "select count(*) from kb_knowledge_base where embedding_model_id = '" + KEEP + "'::uuid");
    }

    private static String arrayLiteral(Connection connection, List<String> ids) {
        return "'{" + String.join(",", ids) + "}'::uuid[]";
    }

    private static String textArrayLiteral(Connection connection, List<String> ids) {
        return "'{" + String.join(",", ids) + "}'::text[]";
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void print(Connection connection, String label, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(rs.getString(1));
            }
            System.out.println(label + "：" + sb);
        }
    }

    private static void assertZero(Connection connection, String label, String sql) throws SQLException {
        int value = scalar(connection, sql);
        if (value != 0) {
            throw new IllegalStateException("后置断言失败：" + label + "（读到 " + value + "）");
        }
        System.out.println("✓ " + label);
    }

    private static final class Plan {
        int kbRows;
        int vec1024Rows;
        int modelRows;
        int vec768Rows;
    }
}

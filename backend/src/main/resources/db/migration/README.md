# 数据库迁移编号规则

Flyway 在 `classpath:db/migration/**` 下**递归扫描所有子目录**，
并把它们的文件名版本号当成**同一套编号**。所以规则只有一条：

> **编号在全库唯一；新增一个迁移 = 取「全部子目录」里的最大号 + 1。**

不要按"我这个目录里最大是几"来选号 —— 这么选过一次，结果和新号撞了：

```
Found more than one migration with version 103
  conversation/V103__conversation_chat_settings_prologue.sql   ← 当时要加的
  platform/V103__platform_guard_settings.sql                    ← 早就存在
```

## 加迁移之前先看一眼全库最大号

```bash
# 在 backend/ 下执行：全部迁移按版本号排序，最后一条就是当前最大号
find src/main/resources/db/migration -name 'V*.sql' \
  | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -1
```

## 改号之后：必须清 `target/classes` 里的旧副本

**这一条比上一条更容易踩，因为改了号之后报错一模一样，看起来像"改号没用"。**

`mvn spring-boot:run` / `process-resources` 只做**增量复制**，
**不会删除**那些「源文件已经没有、但 `target/classes` 里还留着」的旧文件。
于是改了名之后：

```
target/classes/db/migration/conversation/
  V103__conversation_chat_settings_prologue.sql   ← 改名前的残留，照样参与扫描
  V110__conversation_chat_settings_prologue.sql
```

Flyway 仍然看到两个 103，报同一句错。修法：

```bash
# ① 删掉那个残留（路径按实际报错对）
rm -f target/classes/db/migration/conversation/V103__conversation_chat_settings_prologue.sql
# ② 重新同步资源
mvn -o -q process-resources
# ③ 复查：源与产物必须逐文件一致（输出为空才算过）
diff <(cd src/main/resources/db/migration && find . -name 'V*.sql' | sort) \
     <(cd target/classes/db/migration    && find . -name 'V*.sql' | sort)
```

## 复查有没有重复号（两条都要跑）

```bash
# ① 源里有没有两个同号的
find src/main/resources/db/migration -name 'V*.sql' \
  | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | uniq -d
# ② 产物里有没有（残留只可能在这里暴露）
find target/classes/db/migration -name 'V*.sql' \
  | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | uniq -d
```

两条都输出为空才叫没有冲突。

## 已经执行过的迁移不要改内容

Flyway 记的是「版本号 + 校验和」。改一个已经跑过的文件的正文，下次启动会报
`Migration checksum mismatch` —— 那不是编号问题，是"历史被改了"。
需要修正已上线的结构时，加一个新版本。

## 目录划分（只是归属，不影响编号）

| 目录 | 装什么 |
|---|---|
| `platform/` | 全平台共用的基础表与开关 |
| `identity/` | 账号、角色、权限、组织 |
| `knowledge/` | 知识库、文档、分段 |
| `modelcatalog/` | 模型与服务商 |
| `assistant/` | 应用、提示词、模板、编排 |
| `conversation/` | 会话、消息、通用问答设置 |

目录只回答"这段 SQL 属于谁"，**编号仍然是全库一条线**。

-- ★ #83 能力 3：算法备案与安全评估（第十七条）的「留缝」登记位。
--
-- 处置判定 = 留缝：只登记「备案号 + 安全评估结论」两个字段位并可在合规设置页录入、
-- 「关于」可引用；★ 不建申报/变更/注销流程 —— 那是流程系统不是字段，属于 0.5.x 之后的实体。
-- 触发条件（写死）：面向境内公众提供生成式人工智能服务之前，必须把申报与变更注销流程做实
-- （MVP 不适用的合法性依据 = issue #83 引的「未向境内公众提供生成式人工智能服务的，不适用本办法」）。
-- ★ 这一格登记位今天没有下游读数 —— 只有「关于」展示与设置页录入，无闸门消费（见报告）。

alter table platform_compliance_settings
  add column algorithm_filing_no text not null default '',
  add column security_assessment_conclusion text not null default '';

-- 登录日志（#354）。
--
-- ★ 为什么不复用 pf_audit_log：两张表回答的是不同的问题。
--   pf_audit_log 记「谁对平台上的某个资源做了什么」（改库、改规则、看文档），
--   pf_login_log 记「这个人是怎么进门的」（成功、密码错、被锁、被停用、验证码错）。
--   受众也不同：审计给合规看（等保 2.0 可追溯），登录日志给安全看（暴力破解、异地登录排查）。
--   混成一张表的后果是：查「最近 30 天谁在试密码」要在一堆资源变更里捞，而这类查询恰恰是出事时最先要跑的。
--
-- ★ 保留不写 retention：登录日志的留存期要先定口径（等保要求 6 个月，见 #75），
--   在那之前让它只增不减 —— 悄悄过期比留着更危险。

create table pf_login_log (
  id uuid primary key,
  -- 事件时刻。列名用 at 而不是 created_at：它不是"这行被创建的时间"，它就是事件本身的时间。
  at timestamptz not null,
  -- 用户填的那个名字。★ 账号不存在时也要记下来 —— 否则看不出"有人在枚举用户名"。
  username varchar(128) not null,
  -- 解析到的用户；账号不存在时为 null。不对外区分"账号不存在"与"密码错"（那是用户名探测器），
  -- 但日志里必须能区分，否则排查时无法判断是不是打错了用户名。
  user_id uuid,
  -- SUCCESS / BAD_CREDENTIALS / LOCKED / DISABLED / CAPTCHA
  result varchar(24) not null,
  ip varchar(64),
  user_agent varchar(512)
);

comment on table pf_login_log is '登录尝试记录（#354）：成功与被拒都记，只增不改';
comment on column pf_login_log.result is 'SUCCESS / BAD_CREDENTIALS / LOCKED / DISABLED / CAPTCHA';

-- 默认视图就是"最近发生了什么"
create index idx_login_log_at on pf_login_log (at desc);
-- 「这个账号最近被怎么试过」
create index idx_login_log_username_at on pf_login_log (username, at desc);
-- 「最近有多少次被拒」
create index idx_login_log_result_at on pf_login_log (result, at desc);

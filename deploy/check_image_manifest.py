#!/usr/bin/env python3
"""交付镜像与 pom / yml 的对账闸门（`#273`）。

## 这条闸门在防什么

`deploy/Dockerfile` 的 `COPY` 曾经写死 `ai-knowledge-platform-0.0.1-SNAPSHOT.jar`，
而 `backend/pom.xml` 早已是 `0.4.5` —— 交付镜像**构建不出来**，且 CI 从不构建它
（只构建 `deploy/pg/Dockerfile`），所以坏了很久没人知道。

同一个病在别处也犯过：`deploy/README.md` 的示例命令写着 `ai-knowledge-platform:0.3.1`。
共同形状是「两份文件里的名字各写一遍，没有东西把它们钉住」。

## 它查什么（都是静态的，不跑 docker build）

1. Dockerfile 的 jar 通配**确实匹配** `backend/pom.xml` 的 `artifactId-version.jar`
   —— 通配的价值是"版本号往前走不用改镜像"，代价是它**永不报错**；这条断言把代价收回来。
2. `ENTRYPOINT` 使用的路径 == `COPY` 的目标（否则跑起来是 `no such file`）。
3. `EXPOSE` == `application.yml` 的 `server.port` 默认值（端口改了一处不改另一处，编排侧探针会一直不健康）。
4. `USER` 存在且不是 `root`（以 root 跑应用等于把容器逃逸的起点抬高一级）。
5. 这些检查所依赖的文件都读得到 —— 读不到就红（不要用"跳过"冒充"通过"）。

★ 为什么不直接 `docker build`：要守的是"两份文件里的名字还对不对得上"，
  那件事静态就能判、0.1 秒就能判；而 docker build 需要网络、需要 jar 已构建、要几分钟。
  真构建属于交付流水线，不属于快闸 —— 这条分工写在这里，免得下一个人以为"闸门绿 = 镜像能构建"。

用法：
    python deploy/check_image_manifest.py
    python deploy/check_image_manifest.py --self-check
"""

from __future__ import annotations

import fnmatch
import io
import os
import re
import sys
import tempfile

DOCKERFILE = "deploy/Dockerfile"
POM = "backend/pom.xml"
APP_YML = "backend/src/main/resources/application.yml"

COPY_RE = re.compile(r"^\s*COPY\s+(\S+)\s+(\S+)\s*$", re.MULTILINE)
EXPOSE_RE = re.compile(r"^\s*EXPOSE\s+(\d+)\s*$", re.MULTILINE)
USER_RE = re.compile(r"^\s*USER\s+(\S+)\s*$", re.MULTILINE)
ENTRYPOINT_RE = re.compile(r"^\s*ENTRYPOINT\s+\[(.+)\]\s*$", re.MULTILINE)
PORT_RE = re.compile(r"^\s*port:\s*\$\{[A-Z_]+:(\d+)\}", re.MULTILINE)


def _read(path: str) -> str:
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def pom_coordinates(pom_text: str) -> tuple[str, str]:
    """取项目自己的 artifactId / version（跳开 parent 与 dependencyManagement 里的）。"""
    body = re.sub(r"<parent>.*?</parent>", "", pom_text, flags=re.DOTALL)
    body = re.sub(r"<dependencyManagement>.*?</dependencyManagement>", "", body, flags=re.DOTALL)
    artifact = re.search(r"<artifactId>([^<]+)</artifactId>", body)
    version = re.search(r"<version>([^<]+)</version>", body)
    if not artifact or not version:
        raise ValueError("backend/pom.xml 里取不到项目自己的 artifactId / version")
    return artifact.group(1).strip(), version.group(1).strip()


def problems(dockerfile: str, pom: str, app_yml: str) -> list[str]:
    found: list[str] = []
    try:
        artifact, version = pom_coordinates(pom)
    except (ValueError, OSError) as exc:
        return [f"取不到 pom 坐标：{exc}"]

    copies = COPY_RE.findall(dockerfile)
    if not copies:
        found.append("Dockerfile 里没有 COPY 指令 —— 镜像里不会有任何东西")
        return found
    source, target = copies[-1]

    # ① jar 通配必须匹配今天的产物名
    produced = f"{artifact}-{version}.jar"
    pattern = os.path.basename(source)
    if not fnmatch.fnmatch(produced, pattern):
        found.append(
            f"Dockerfile 的 COPY 源是 `{source}`，而 pom 今天产出的是 `{produced}` "
            f"—— 通配不匹配等于 COPY 找不到文件，镜像构建会失败（且 CI 不构建它）"
        )

    # ② ENTRYPOINT 必须指向 COPY 的目标
    entry = ENTRYPOINT_RE.search(dockerfile)
    if not entry:
        found.append("Dockerfile 没有 ENTRYPOINT —— 镜像起来什么也不做")
    else:
        joined = entry.group(1)
        if f'"{target}"' not in joined:
            found.append(f"ENTRYPOINT 里没有 COPY 的目标 `{target}` —— 起来会是 no such file")

    # ③ EXPOSE 与 yml 的 server.port 默认值一致
    expose = EXPOSE_RE.search(dockerfile)
    port = PORT_RE.search(app_yml)
    if not expose:
        found.append("Dockerfile 没有 EXPOSE —— 编排侧拿不到端口声明")
    elif not port:
        found.append("application.yml 里找不到 `port: ${ENV:数字}` 这一行 —— 端口默认值无从对账")
    elif expose.group(1) != port.group(1):
        found.append(
            f"EXPOSE {expose.group(1)} 与 application.yml 的 server.port 默认 {port.group(1)} 不一致 "
            f"—— 编排侧探针会一直不健康，而容器日志看起来一切正常"
        )

    # ④ 不以 root 跑
    user = USER_RE.search(dockerfile)
    if not user:
        found.append("Dockerfile 没有 USER —— 容器以 root 运行")
    elif user.group(1).strip() in {"root", "0"}:
        found.append("USER 是 root —— 应用只需要读自己的 jar，不需要 root")

    return found


def check(dockerfile: str = DOCKERFILE, pom: str = POM, app_yml: str = APP_YML) -> int:
    missing = [p for p in (dockerfile, pom, app_yml) if not os.path.isfile(p)]
    if missing:
        for path in missing:
            print(f"  ✗ 读不到 {path} —— 缺文件不等于检查通过")
        return 1

    found = problems(_read(dockerfile), _read(pom), _read(app_yml))
    for problem in found:
        print(f"  ✗ {problem}")
    if found:
        print(f"\n结果：红 —— {len(found)} 处对不上")
        return 1

    artifact, version = pom_coordinates(_read(pom))
    print(f"✓ 交付镜像与 pom / yml 对得上（产物 {artifact}-{version}.jar，端口与 USER 一致）")
    return 0


GOOD_DOCKERFILE = """
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY backend/target/ai-knowledge-platform-*.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
"""
GOOD_POM = "<project><artifactId>ai-knowledge-platform</artifactId><version>0.4.5</version></project>"
GOOD_YML = "server:\n  port: ${SERVER_PORT:8080}\n"


def self_check() -> int:
    failures = 0

    def expect(name: str, dockerfile: str, pom: str, yml: str, want: int) -> None:
        nonlocal failures
        with tempfile.TemporaryDirectory() as tmp:
            paths = {}
            for key, text in (("df", dockerfile), ("pom", pom), ("yml", yml)):
                path = os.path.join(tmp, key)
                with io.open(path, "w", encoding="utf-8") as handle:
                    handle.write(text)
                paths[key] = path
            got = len(problems(_read(paths["df"]), _read(paths["pom"]), _read(paths["yml"])))
        ok = got == want
        print(f"  {'✓' if ok else '✗'} {name}（期望 {want} 处，实得 {got} 处）")
        if not ok:
            failures += 1

    expect("全对 ⇒ 0 处", GOOD_DOCKERFILE, GOOD_POM, GOOD_YML, 0)
    expect(
        "写死旧版本号 ⇒ 1 处（#273 的原始形状）",
        GOOD_DOCKERFILE.replace("ai-knowledge-platform-*.jar", "ai-knowledge-platform-0.0.1-SNAPSHOT.jar"),
        GOOD_POM,
        GOOD_YML,
        1,
    )
    expect(
        "artifactId 改名而 Dockerfile 没跟上 ⇒ 1 处",
        GOOD_DOCKERFILE,
        GOOD_POM.replace("ai-knowledge-platform", "ai-kb"),
        GOOD_YML,
        1,
    )
    expect(
        "ENTRYPOINT 指向别的路径 ⇒ 1 处",
        GOOD_DOCKERFILE.replace('ENTRYPOINT ["java", "-jar", "/app/app.jar"]', 'ENTRYPOINT ["java", "-jar", "/app/other.jar"]'),
        GOOD_POM,
        GOOD_YML,
        1,
    )
    expect("EXPOSE 与 yml 端口不一致 ⇒ 1 处", GOOD_DOCKERFILE.replace("8080", "8081"), GOOD_POM, GOOD_YML, 1)
    expect("没有 USER ⇒ 1 处", GOOD_DOCKERFILE.replace("USER appuser\n", ""), GOOD_POM, GOOD_YML, 1)
    expect("USER root ⇒ 1 处", GOOD_DOCKERFILE.replace("USER appuser", "USER root"), GOOD_POM, GOOD_YML, 1)
    expect("没有 EXPOSE ⇒ 1 处", GOOD_DOCKERFILE.replace("EXPOSE 8080\n", ""), GOOD_POM, GOOD_YML, 1)
    expect("parent 里的版本号不许被误当成项目版本 ⇒ 0 处", GOOD_DOCKERFILE, "<project><parent><artifactId>p</artifactId><version>9.9.9</version></parent>" + GOOD_POM[len("<project>") :], GOOD_YML, 0)

    # 两条真退码用例：缺文件必须红
    with tempfile.TemporaryDirectory() as tmp:
        if check(os.path.join(tmp, "nope"), POM, APP_YML) == 1:
            print("  ✓ Dockerfile 缺失 ⇒ 退 1")
        else:
            print("  ✗ Dockerfile 缺失却退 0")
            failures += 1

    print(f"\n自检：{'通过' if failures == 0 else f'{failures} 条不过'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    if "--self-check" in sys.argv[1:]:
        sys.exit(self_check())
    sys.exit(check())

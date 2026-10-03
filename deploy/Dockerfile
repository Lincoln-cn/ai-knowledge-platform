# ════════════════════════════════════════════════════════════════════════════
#  应用镜像（部署形态）—— ★ OCR 引擎烤进镜像，交付后**运行时不依赖互联网**
# ════════════════════════════════════════════════════════════════════════════
#
#  为什么单独一份（与根 docker-compose.yml 的关系）：
#    · 根 docker-compose.yml 是**开发期**形态（只起 PG，应用在本地跑，ROADMAP §4.6）；
#    · 这一份是**交付**形态。两者关注点不同，混在一起会让"改代码不重建镜像"这条开发便利消失。
#
#  ★★ 本文件的核心承诺：OCR 在交付后**不需要任何外部依赖**。
#     设计册 12 §11.2 的选型是"CLI Tesseract + tessdata，零新服务、零新库"，
#     而它的落地形态就是下面那三行 apt —— 引擎与语言包都进镜像层，
#     于是内网/离线环境拿到镜像就能跑，不需要再装任何东西、也不需要联网拉语言包。
#
#  ★ 已实测（不是照文档抄的）：
#     · 基础镜像 eclipse-temurin:25-jre = Ubuntu 26.04.1 LTS + OpenJDK 25.0.4.1
#     · 三个 apt 包装得上，tesseract 版本 **5.5.0**（与开发机同版本 ⇒ 识别行为一致）
#     · tessdata 落在 /usr/share/tesseract-ocr/5/tessdata/，含 chi_sim / eng（外加 osd）
#       （Debian bookworm 也验过：tesseract 5.3.0、同一路径）
#
#  构建（在仓库根执行，构建上下文就是仓库根）：
#     backend\mvn-jdk25.cmd -B -f backend/pom.xml package -DskipTests
#     docker build -f deploy/Dockerfile -t ai-knowledge-platform:dev .
#
#  自检（镜像里 OCR 真的可用 —— 部署验收的第一步）：
#     docker run --rm ai-knowledge-platform:dev tesseract --list-langs
#     → 必须列出 chi_sim 与 eng；只有 eng 说明漏装了语言包
# ════════════════════════════════════════════════════════════════════════════

FROM eclipse-temurin:25-jre

# ── OCR 引擎与语言包（12 §11.2）──
# ★ --no-install-recommends + 清 apt 列表：镜像里不留用不上的东西，
#   也避免把 apt 缓存带进交付物（内网镜像通常要过安全扫描）。
# ★ 语言包**必须显式列**，别指望 `tesseract-ocr` 自带 —— 官方包只带 eng，
#   漏了 chi_sim 的症状是"引擎正常、中文全成方块"，且日志里一句错都没有。
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      tesseract-ocr \
      tesseract-ocr-chi-sim \
      tesseract-ocr-eng \
 && rm -rf /var/lib/apt/lists/*

# ★ 12 §11.2 的坑 ①：tessdata 路径**必须显式给**。
#   给了它，应用侧的 OcrEngine 才能去数"语言包在不在"并把结论报进 /actuator/health；
#   不给就等于回到"环境里恰好有" —— 而这正是本项目最不想要的偶然后果。
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

# ── 应用 ──
# ★ 不以 root 跑：应用只需读自己的 jar，没有任何需要 root 的动作。
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
WORKDIR /app

# ★ 只 COPY jar：构建上下文里还有源码、node_modules、target 下的其它产物，
#   全 COPY 进来会让镜像体积与"镜像里有什么"一起失控。
COPY backend/target/ai-knowledge-platform-0.0.1-SNAPSHOT.jar /app/app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# ★ 不做 HEALTHCHECK：判健康的正确入口是 /actuator/health 的 components.ocr，
#   而镜像里没有 curl/wget。编排侧（compose/k8s）用 HTTP 探针查它即可 ——
#   验收判据是 components.ocr.details.available == true，见 deploy/README.md。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

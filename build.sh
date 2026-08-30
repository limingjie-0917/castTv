#!/usr/bin/env bash
# castTv 构建包装：固定 JDK 17 + 可选「轻量 / 重度」并行度 + CPU 限幅后台构建。
# 用法：
#   ./build.sh                              # assembleDebug（CPU-lite：workers.max=2，gradle.properties 默认）
#   ./build.sh release                      # assembleRelease
#   ./build.sh -j 1 assembleDebug           # 单核（后台追剧/投屏期间构建）
#   ./build.sh --insane -x lint             # 拉满 8+16G 机器：并行 + workers=4（仅限 CI / 新机器）
#   ./build.sh --in-background assembleRelease  # taskpolicy 低 QoS + nice 背景限幅构建（CPU≤40% 平均）
# 注：本脚本默认使用 Gradle 常驻 daemon；--no-daemon 会被自动移除避免 JIT 反复预热。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# ---------- 环境语义：本地构建关闭 CI 模式 ----------
# 很多终端 IDE/agent 启动器默认注入 CI=true，会触发 Gradle 构建脚本里 bumpVersion
# 「禁止写回 build.gradle.kts」的分支，导致本地手动构建后版本基线与 APK 不一致。
# 本脚本以「本地迭代」为默认语义，强制 unset；CI 流水线请直接调用 ./gradlew 而不是走本脚本。
unset CI
unset GITHUB_ACTIONS
unset BUILD_NUMBER

# ---------- 工具链固定 ----------
JAVA_HOME="${CASTTV_JAVA_HOME:-/Users/limingjie/gradle-install/jdk-17.0.20.1+1/Contents/Home}"
ANDROID_SDK_ROOT="${CASTTV_ANDROID_SDK:-/Users/limingjie/Library/Android/sdk}"
export JAVA_HOME ANDROID_SDK_ROOT ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "× 找不到 JDK 17：$JAVA_HOME" >&2
  echo "  请通过 CASTTV_JAVA_HOME=/path/to/jdk17 指定，或下载 Temurin 17 到默认路径。" >&2
  exit 2
fi

# ---------- 参数解析 ----------
TASK=""
JOBS=""                  # 空 → 使用 gradle.properties 的 workers.max=2
EXTRA=("-x" "lint")       # lint 重（每 variant 静态扫描 ≈ 编译 30% CPU），本地迭代默认跳过；CI 显式运行 lintDebug/lintRelease。
REMOVE_NO_DAEMON=1
INSANE=0
BG_LIMIT=0
QUIET=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    debug)                 TASK="assembleDebug"; shift ;;
    release)               TASK="assembleRelease"; shift ;;
    assembleDebug|assembleRelease|assemble|build|clean)
                           TASK="$1"; shift ;;
    -j|--jobs|--workers)   JOBS="$2"; shift 2 ;;
    -j=*|--jobs=*|--workers=*) JOBS="${1#*=}"; shift ;;
    --insane|--all-cores)  INSANE=1; shift ;;
    --in-background|--cpu-lite-bg) BG_LIMIT=1; shift ;;
    -q|--quiet)            QUIET=1; shift ;;
    --no-daemon)           shift ;;  # 主动丢弃：禁止使用 --no-daemon
    -x|--exclude-task)     EXTRA+=("-x" "$2"); shift 2 ;;
    --)                    shift; break ;;
    -D*|-P*|--console=*|--rerun-tasks|--build-cache|--no-build-cache|--configuration-cache|--no-configuration-cache)
                           EXTRA+=("$1"); shift ;;
    -h|--help)
      sed -n '1,16p' "$0"; exit 0 ;;
    *)
      echo "? 忽略未知参数（透传给 Gradle）：$1" >&2
      EXTRA+=("$1"); shift ;;
  esac
done
# 允许把多个 Gradle 任务从 -- 之后继续补进来
for x in "$@"; do EXTRA+=("$x"); done

: "${TASK:=assembleDebug}"

# ---------- 并行度覆写 ----------
WORKER_ARGS=()
if [[ "$INSANE" -eq 1 ]]; then
  WORKER_ARGS+=("-Dorg.gradle.workers.max=4" "-Dorg.gradle.parallel=true")
elif [[ -n "${JOBS:-}" ]]; then
  WORKER_ARGS+=("-Dorg.gradle.workers.max=$JOBS" "-Dorg.gradle.parallel=false")
  # 4 核机器再追加一层：Kotlin daemon 也降并行，避免与 Gradle worker 争抢
  if [[ "$JOBS" -le 2 ]]; then
    WORKER_ARGS+=("-Dkotlin.compiler.execution.strategy=in-process")
  fi
fi

# assembleRelease 默认也跳过 debug 的 lint 任务；release lint 则保留（如要完全跳过用 -x lintVitalAnalyzeRelease 显式传）
if [[ "$TASK" == "assembleRelease" ]]; then
  : # 显式留白：这里可以追加 release 专属跳过项
fi

# ---------- 命令组装 ----------
GRADLE_CMD=(./gradlew "$TASK" "${WORKER_ARGS[@]+"${WORKER_ARGS[@]}"}" "${EXTRA[@]}")
if [[ "$QUIET" -eq 1 ]]; then GRADLE_CMD+=("--quiet"); fi

echo "▶ JDK  : $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
echo "▶ Tasks: $TASK"
echo "▶ Workers.parallelism: ${JOBS:-gradle.properties=2}  insane=${INSANE}  bg_limit=${BG_LIMIT}"
echo "▶ CLI  : ${GRADLE_CMD[*]}"
echo "--------------------------------------------------------------------------"

if [[ "$BG_LIMIT" -eq 1 ]]; then
  # macOS：taskpolicy 把当前命令降级为 background QoS（CPU 配额约 25~50%），
  # nice -n 10 再让出调度优先级，保障投屏 / IDE / Web 浏览不卡。
  exec taskpolicy -b nice -n 10 "${GRADLE_CMD[@]}"
else
  exec nice -n 5 "${GRADLE_CMD[@]}"
fi

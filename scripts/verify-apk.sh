#!/usr/bin/env bash
# =============================================================================
# 《道枢罗盘》APK 签名校验脚本
# -----------------------------------------------------------------------------
# 用途：调用 apksigner 校验 APK 签名，并断言存在**现代签名方案**（v2 或 v3 至少一项为真）。
#       兼容 Android 11–15（targetSdk >= 30 的包要求 APK Signature Scheme v2 或更高）。
#
# 为什么不断言 v1/v2 也为真（重要，勿改回）：
#   `apksigner verify --verbose` 的 v1/v2/v3 行**不是“APK 内有哪些签名”的清单**，
#   而是“按目标 SDK 区间实际用哪个方案完成校验”的结果；apksigner 默认取
#   AndroidManifest 的 minSdk/targetSdk，本工程为 30..35，此时只有 v3 记为 true。
#   用真实产物（minSdk 30，enableV1/V2/V3Signing 均为 true）实测同一 APK：
#       默认(30..35)     -> v1=false v2=false v3=true
#       --min/max 28..29 -> v3=true
#       --min/max 24..27 -> v2=true
#       --min/max 23..23 -> v1=true
#   三种签名其实都在：META-INF/MANIFEST.MF + CERT.SF + CERT.RSA 存在且
#   `jarsigner -verify` 判定 jar 已验证；APK Signing Block 内同时含 v2(0x7109871a)
#   与 v3(0xf05368c0) 块。因此若要求 v1/v2 同为 true，CI 必然失败。
#
# 用法：
#   bash scripts/verify-apk.sh                                   # 自动查找 release 产物
#   bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
#   bash scripts/verify-apk.sh dist/*.apk                        # 可传多个
#
# apksigner 探测顺序：
#   1) PATH 中的 apksigner；
#   2) $ANDROID_HOME/build-tools/*/apksigner（取版本号最大者）；
#   3) $ANDROID_SDK_ROOT/build-tools/*/apksigner。
# 退出码：0 表示全部通过；非 0 表示存在签名缺失或校验失败。
# =============================================================================
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

# ---------- 定位 apksigner ----------
find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return 0
  fi
  local base candidate
  for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$base" ] || continue
    [ -d "$base/build-tools" ] || continue
    candidate="$(ls -1d "$base"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
    # Windows（Git Bash / MSYS）下可执行文件名为 apksigner.bat
    candidate="$(ls -1d "$base"/build-tools/*/apksigner.bat 2>/dev/null | sort -V | tail -n 1 || true)"
    if [ -n "$candidate" ] && [ -f "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

if ! apksigner_bin="$(find_apksigner)"; then
  echo "错误：未找到 apksigner。请安装 Android SDK build-tools 并设置 ANDROID_HOME。" >&2
  echo "      CI 中由 android-actions/setup-android 安装 build-tools;35.0.0 提供。" >&2
  exit 1
fi
echo "使用 apksigner：$apksigner_bin"

# Windows（Git Bash / MSYS）下 .bat 包装脚本经 cmd.exe 传参时，含空格的绝对路径会丢引号
# 而失败（报 'E:\DeepSeek' is not recognized）。若旁边存在 lib/apksigner.jar，则直接
# 用 java -jar 调用（与 .bat 行为等价），彻底绕开 cmd.exe 的引号问题。Linux/CI 不受影响。
apksigner_cmd=("$apksigner_bin")
case "$apksigner_bin" in
  *.bat|*.cmd)
    apksigner_lib="$(dirname -- "$apksigner_bin")/lib/apksigner.jar"
    if [ -f "$apksigner_lib" ]; then
      java_bin=""
      if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_bin="$JAVA_HOME/bin/java"
      elif command -v java >/dev/null 2>&1; then
        java_bin="$(command -v java)"
      fi
      if [ -n "$java_bin" ]; then
        apksigner_cmd=("$java_bin" -jar "$apksigner_lib")
        echo "（Windows .bat 包装脚本改用 java -jar：$apksigner_lib）"
      fi
    fi
    ;;
esac
"${apksigner_cmd[@]}" version || true

# ---------- Windows 路径兼容 ----------
# Git Bash / MSYS 下若传入 `E:\path\app-release.apk` 这类 Windows 风格路径，
# 直接交给 apksigner（.bat）会因反斜杠与盘符被误解析而失败；此处仅在 Windows
# shell 下把 `E:\a\b` 规范化为 `/e/a/b`。Linux/macOS（含 CI 的 ubuntu-latest）不变。
normalize_input_path() {
  local p="$1"
  case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
      case "$p" in
        [A-Za-z]:[\\/]*)
          local drive rest
          drive="$(printf '%s' "${p:0:1}" | tr '[:upper:]' '[:lower:]')"
          rest="${p:2}"
          rest="${rest//\\//}"
          printf '/%s%s\n' "$drive" "$rest"
          return 0
          ;;
      esac
      ;;
  esac
  printf '%s\n' "$p"
}

# ---------- 收集待校验 APK ----------
apks=("$@")
if [ "${#apks[@]}" -eq 0 ]; then
  preferred="$repo_root/app/build/outputs/apk/release/app-release.apk"
  if [ -f "$preferred" ]; then
    apks=("$preferred")
  else
    # 兜底：在 release 输出目录中查找（排除 unsigned）
    mapfile -t found < <(ls -1 "$repo_root"/app/build/outputs/apk/release/*.apk 2>/dev/null | grep -v 'unsigned' || true)
    apks=("${found[@]}")
  fi
else
  for i in "${!apks[@]}"; do
    apks[$i]="$(normalize_input_path "${apks[$i]}")"
  done
fi

if [ "${#apks[@]}" -eq 0 ]; then
  echo "错误：未找到待校验的 APK。请先执行 ./gradlew assembleRelease。" >&2
  exit 1
fi

status=0

for apk in "${apks[@]}"; do
  echo ""
  echo "==================== 校验 $apk ===================="
  if [ ! -f "$apk" ]; then
    echo "错误：文件不存在：$apk" >&2
    status=1
    continue
  fi

  # --verbose 输出各签名方案明细；使用 tee 便于断言，同时完整展示在日志中
  report="$(mktemp)"
  if ! "${apksigner_cmd[@]}" verify --verbose --print-certs "$apk" | tee "$report"; then
    echo "错误：apksigner verify 失败：$apk" >&2
    rm -f "$report"
    status=1
    continue
  fi

  # ---------------------------------------------------------------------------
  # 签名方案断言：至少 v2 或 v3 之一为 true（详见文件头说明）。
  #   * v2/v3 为 false 可能只是“该 SDK 区间未选用该方案”的上报结果，不代表签名缺失；
  #   * v1 属可选接受项（minSdk 30 时默认区间不校验 v1），v4 按 build.gradle.kts 约定关闭；
  #   * apksigner 自身退出码已在上面判定：能走到这里就说明签名块自洽、APK 可验证。
  # ---------------------------------------------------------------------------
  while IFS= read -r line; do
    echo "  签名方案状态：${line}"
  done < <(grep -E '^Verified using ' "$report" || true)

  if grep -qE 'Verified using v2 scheme[^:]*:[[:space:]]*true' "$report" \
     || grep -qE 'Verified using v3 scheme[^:]*:[[:space:]]*true' "$report"; then
    echo "OK：签名方案校验通过（v2 或 v3 至少一项为 true；minSdk=30 时通常仅 v3 为 true）"
  else
    echo "错误：未检测到 v2/v3 签名（仅有 v1 或不满足 Android 11+ 的签名要求）：$apk" >&2
    status=1
  fi
  rm -f "$report"
done

echo ""
if [ "$status" -eq 0 ]; then
  echo "全部 APK 签名校验通过（v2 或 v3 现代方案）。"
else
  echo "存在签名校验失败项，请检查签名配置与 Secrets。" >&2
fi
exit "$status"

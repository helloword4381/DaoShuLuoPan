#!/usr/bin/env bash
# =============================================================================
# 《道枢罗盘》keystore 单行 base64 编码脚本
# -----------------------------------------------------------------------------
# 用途：把本地 keystore 编码成单行 base64，用于配置 GitHub Secret
#       ANDROID_KEYSTORE_BASE64（CI 侧解码到 $RUNNER_TEMP/release-key.jks）。
#
# 用法：
#   bash scripts/encode-keystore.sh                  # 默认读 <工程根>/release-key.jks
#   bash scripts/encode-keystore.sh /path/key.jks    # 指定路径
#   bash scripts/encode-keystore.sh release-key.jks > /tmp/daoshu-key.b64
#
# 输出：stdout 仅包含一行 base64（便于重定向），其余提示信息全部走 stderr。
#
# 安全提示（必读）：
#   1) 该 base64 等同于签名私钥，属于最高级别机密，禁止粘贴到聊天/issue/CI 日志；
#   2) 建议重定向到临时文件，导入 GitHub Secrets 后立即安全删除；
#   3) 任何 CI 步骤都不得回显该内容——build-apk.yml 仅通过 secrets 注入，从不打印。
# =============================================================================
set -euo pipefail

# 定位脚本所在目录与工程根，保证在任意工作目录下执行都正确
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

keystore_path="${1:-$repo_root/release-key.jks}"

if [ ! -f "$keystore_path" ]; then
  echo "错误：找不到 keystore 文件：$keystore_path" >&2
  echo "提示：先执行 bash scripts/generate-keystore.sh 生成密钥。" >&2
  exit 1
fi

if [ ! -s "$keystore_path" ]; then
  echo "错误：keystore 文件为空：$keystore_path" >&2
  exit 1
fi

# 输出单行 base64：优先 base64，其次 openssl，两者都缺失则报错
encode_one_line() {
  local file="$1"
  if command -v base64 >/dev/null 2>&1; then
    base64 < "$file" | tr -d '\r\n'
  elif command -v openssl >/dev/null 2>&1; then
    openssl base64 -A -in "$file"
  else
    echo "错误：系统缺少 base64 与 openssl，无法编码。" >&2
    return 1
  fi
}

echo "警告：以下输出为签名私钥材料，请勿公开或粘贴到任何日志中。" >&2
echo "来源文件：$keystore_path（大小 $(wc -c < "$keystore_path" | tr -d ' ') 字节）" >&2
echo "用途：粘贴到仓库 Secret ANDROID_KEYSTORE_BASE64。" >&2

encode_one_line "$keystore_path"
echo "" >&2

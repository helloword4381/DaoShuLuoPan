#!/usr/bin/env bash
# =============================================================================
# 《道枢罗盘》Android 发布签名 keystore 生成脚本
# -----------------------------------------------------------------------------
# 用途：用 keytool 生成 release 签名密钥，并打印 GitHub Secrets 配置指引。
#       生成的 keystore 默认落在 <工程根>/release-key.jks，与
#       app/build.gradle.kts 中 `rootProject.file("release-key.jks")` 的回退路径一致。
#
# 用法：
#   bash scripts/generate-keystore.sh
#   bash scripts/generate-keystore.sh -o /secure/release-key.jks -a daoshu -v 10950
#   bash scripts/generate-keystore.sh -f          # 覆盖已存在的文件
#
# 参数：
#   -o <路径>   输出路径（默认 <工程根>/release-key.jks）
#   -a <别名>   密钥别名（默认 daoshu-compass，与 docs/发布流程.md 的建议一致）
#   -v <天数>   有效期天数（默认 10000，约 27 年）
#   -s <主题>   DN 主题（默认见下方 default_dname，可用环境变量 DAOSHU_KEY_DNAME 覆盖）
#   -f          允许覆盖已存在的 keystore
#
# 口令来源（优先环境变量，缺失时交互式静默输入，绝不作为命令行参数）：
#   ANDROID_STORE_PASSWORD   keystore（store）口令，至少 6 位
#   ANDROID_KEY_PASSWORD     key 口令；留空则与 store 口令相同
#
# 安全：本脚本不会把任何口令写入仓库文件；生成的 keystore 已被 .gitignore 排除。
# =============================================================================
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"

out_path="$repo_root/release-key.jks"
key_alias="daoshu-compass"
validity_days="10000"
default_dname="CN=DaoShu Compass, OU=Release, O=DaoShu, L=Beijing, ST=Beijing, C=CN"
dname="${DAOSHU_KEY_DNAME:-$default_dname}"
force="no"

usage() {
  sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
  case "$1" in
    -o) out_path="${2:?缺少 -o 的参数值}"; shift 2 ;;
    -a) key_alias="${2:?缺少 -a 的参数值}"; shift 2 ;;
    -v) validity_days="${2:?缺少 -v 的参数值}"; shift 2 ;;
    -s) dname="${2:?缺少 -s 的参数值}"; shift 2 ;;
    -f) force="yes"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "错误：未知参数 $1（用 -h 查看用法）" >&2; exit 1 ;;
  esac
done

# ---------- 定位 keytool ----------
keytool_bin=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  keytool_bin="$JAVA_HOME/bin/keytool"
elif command -v keytool >/dev/null 2>&1; then
  keytool_bin="$(command -v keytool)"
fi
if [ -z "$keytool_bin" ]; then
  echo "错误：未找到 keytool，请先安装 JDK 17 并设置 JAVA_HOME（CI 使用 temurin 17）。" >&2
  exit 1
fi
echo "使用 keytool：$keytool_bin"

# ---------- 覆盖保护 ----------
if [ -e "$out_path" ] && [ "$force" != "yes" ]; then
  echo "错误：目标文件已存在：$out_path" >&2
  echo "提示：如需重新生成请加 -f；注意覆盖会导致已发布版本无法升级，且旧密钥将永久失效。" >&2
  exit 1
fi

# ---------- 口令收集 ----------
if [ -z "${ANDROID_STORE_PASSWORD:-}" ]; then
  read -r -s -p "请输入 keystore 口令（不回显，至少 6 位）：" ANDROID_STORE_PASSWORD
  echo "" >&2
fi
if [ -z "${ANDROID_KEY_PASSWORD:-}" ]; then
  ANDROID_KEY_PASSWORD="$ANDROID_STORE_PASSWORD"
fi
if [ "${#ANDROID_STORE_PASSWORD}" -lt 6 ]; then
  echo "错误：keystore 口令至少需要 6 位（keytool 限制）。" >&2
  exit 1
fi
if [ "${#ANDROID_KEY_PASSWORD}" -lt 6 ]; then
  echo "错误：key 口令至少需要 6 位（keytool 限制）。" >&2
  exit 1
fi
# 仅导出为子进程环境变量，避免出现在 ps/命令行历史中
export ANDROID_STORE_PASSWORD ANDROID_KEY_PASSWORD

# ---------- 生成密钥 ----------
mkdir -p "$(dirname -- "$out_path")"
umask 077

"$keytool_bin" -genkeypair \
  -keystore "$out_path" \
  -storetype JKS \
  -alias "$key_alias" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity "$validity_days" \
  -dname "$dname" \
  -storepass:env ANDROID_STORE_PASSWORD \
  -keypass:env ANDROID_KEY_PASSWORD

chmod 600 "$out_path" 2>/dev/null || true

# ---------- 校验（只输出别名/算法等公开信息） ----------
"$keytool_bin" -list -v \
  -keystore "$out_path" \
  -storetype JKS \
  -storepass:env ANDROID_STORE_PASSWORD \
  | grep -E '别名|Alias|算法|Algorithm|有效期|Valid from|条目类型|Entry type' || true

cat <<EOF

================================================================================
密钥生成完成
--------------------------------------------------------------------------------
keystore 路径 : $out_path
别名(alias)   : $key_alias
有效期        : $validity_days 天
--------------------------------------------------------------------------------
下一步：配置以下 4 个 GitHub Secrets
  Settings -> Secrets and variables -> Actions -> New repository secret

  1) ANDROID_KEYSTORE_BASE64  ：执行下面命令，把输出的**一行 base64** 整体粘贴进去
       bash scripts/encode-keystore.sh "$out_path" > /tmp/daoshu-key.b64
  2) ANDROID_KEY_ALIAS        ：$key_alias
  3) ANDROID_KEY_PASSWORD     ：你刚才输入的 key 口令
  4) ANDROID_STORE_PASSWORD   ：你刚才输入的 keystore 口令

本地签名构建（可选，三个口令变量需自行导出）：
  export KEYSTORE_PATH="$out_path"
  export ANDROID_KEY_ALIAS="$key_alias"
  export ANDROID_KEY_PASSWORD='<key 口令>'
  export ANDROID_STORE_PASSWORD='<store 口令>'
  ./gradlew assembleRelease

注意：
  * $out_path 已被 .gitignore 排除，但仍请确认绝不提交；
  * CI 不读取该本地文件，只使用 Secrets 解码到 \$RUNNER_TEMP 的临时副本；
  * 请立刻把 keystore 与两个口令备份到密码管理器，密钥丢失将无法发布覆盖更新。
================================================================================
EOF

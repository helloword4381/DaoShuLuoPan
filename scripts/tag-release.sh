#!/usr/bin/env bash
# =============================================================================
# 《道枢罗盘》打标签发布脚本
# -----------------------------------------------------------------------------
# 用途：从 app/build.gradle.kts 读取 appVersionName（回退 versionName），
#       创建注解标签 v{versionName} 并推送到远程，触发 build-apk.yml 的
#       "push tag v*" 分支，由 CI 构建签名 APK 并创建 GitHub Release。
#
# 用法：
#   bash scripts/tag-release.sh                 # 交互确认后打标签并推送
#   bash scripts/tag-release.sh --dry-run       # 只打印将要执行的操作
#   bash scripts/tag-release.sh --yes           # 跳过交互确认（CI/自动化用）
#   bash scripts/tag-release.sh -r upstream -m "首个正式版"
#
# 参数：
#   -r <远程名>   默认 origin
#   -m <说明>     标签说明，默认 "道枢罗盘 v{versionName}"
#   --dry-run     仅演练
#   --yes         跳过确认
#
# 前置要求：工作区已提交（无未提交的已跟踪改动），且当前提交已推送到目标分支。
# =============================================================================
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
cd "$repo_root"

remote="origin"
tag_message=""
dry_run="no"
assume_yes="no"

while [ $# -gt 0 ]; do
  case "$1" in
    -r) remote="${2:?缺少 -r 的参数值}"; shift 2 ;;
    -m) tag_message="${2:?缺少 -m 的参数值}"; shift 2 ;;
    --dry-run) dry_run="yes"; shift ;;
    --yes) assume_yes="yes"; shift ;;
    -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "错误：未知参数 $1（用 -h 查看用法）" >&2; exit 1 ;;
  esac
done

command -v git >/dev/null 2>&1 || { echo "错误：未找到 git。" >&2; exit 1; }
[ -d .git ] || { echo "错误：当前目录不是 git 仓库：$repo_root" >&2; exit 1; }
[ -f app/build.gradle.kts ] || { echo "错误：找不到 app/build.gradle.kts。" >&2; exit 1; }

# ---------- 读取版本号 ----------
version_name="$(grep -E '^[[:space:]]*val[[:space:]]+appVersionName' app/build.gradle.kts | head -n 1 | sed -E 's/.*"([^"]*)".*/\1/')"
if [ -z "$version_name" ]; then
  version_name="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' app/build.gradle.kts | head -n 1 | sed -E 's/.*"([^"]*)".*/\1/')"
fi
if [ -z "$version_name" ]; then
  echo "错误：无法从 app/build.gradle.kts 解析 versionName。" >&2
  exit 1
fi

version_code="$(grep -E '^[[:space:]]*val[[:space:]]+appVersionCode' app/build.gradle.kts | head -n 1 | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/')"
[ -n "$version_code" ] || version_code="未知"

tag="v${version_name}"
[ -n "$tag_message" ] || tag_message="道枢罗盘 v${version_name}（versionCode ${version_code}）"

echo "versionName = $version_name"
echo "versionCode = $version_code"
echo "标签        = $tag"
echo "远程        = $remote"

# ---------- 工作区与标签检查 ----------
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "错误：存在未提交的已跟踪改动，请先提交后再打发布标签。" >&2
  git status --short >&2
  exit 1
fi

if git rev-parse -q --verify "refs/tags/${tag}" >/dev/null; then
  echo "错误：标签 ${tag} 已存在。" >&2
  echo "提示：版本号递增后重新执行；如需移动标签请手动处理（发布过的标签不应移动）。" >&2
  exit 1
fi

current_branch="$(git rev-parse --abbrev-ref HEAD)"
echo "当前分支    = $current_branch"
echo "当前提交    = $(git rev-parse --short HEAD)"

# ---------- 演练模式 ----------
if [ "$dry_run" = "yes" ]; then
  echo ""
  echo "[dry-run] 将执行："
  echo "  git tag -a $tag -m \"$tag_message\""
  echo "  git push $remote $tag"
  echo "[dry-run] 未做任何改动。"
  exit 0
fi

# ---------- 交互确认 ----------
if [ "$assume_yes" != "yes" ]; then
  printf '确认创建并推送标签 %s ？[y/N] ' "$tag"
  read -r answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *) echo "已取消。"; exit 0 ;;
  esac
fi

# ---------- 打标签并推送 ----------
git tag -a "$tag" -m "$tag_message"
echo "已创建本地标签：$tag"

if ! git push "$remote" "$tag"; then
  echo "错误：推送标签失败。若标签已在本地创建，可修复权限后重试：" >&2
  echo "  git push $remote $tag" >&2
  exit 1
fi

cat <<EOF

标签已推送：$remote/$tag
CI 将自动执行 .github/workflows/build-apk.yml：
  1) 构建并签名 release APK；
  2) 回推更新后的 release/version.json 到 main（提交信息含 [skip ci]）；
  3) 创建 GitHub Release 并附加资产 daoshu-compass-v${version_name}.apk。

客户端自动更新会读取：
  https://raw.githubusercontent.com/helloword4381/DaoShuLuoPan/main/release/version.json
EOF

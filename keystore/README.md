# keystore 目录说明（严禁提交密钥）

本目录**只保留** `README.md` 与 `.gitkeep` 两个占位文件，用于说明签名密钥的生成与托管方式。

> **绝对不要把 `*.jks` / `*.keystore` / `key.properties` 放进本目录并提交。**
> 仓库根 `.gitignore` 已排除 `*.jks`、`*.keystore`、`release-key.jks`、`key.properties`、
> `keystore.properties`、`*.p12`、`*.pfx`、`*.pem`。

## 1. 密钥文件放在哪里

`app/build.gradle.kts` 的签名配置只从**环境变量**读取，按以下优先级取 keystore 路径：

1. 环境变量 `KEYSTORE_PATH`（已解码的 keystore 绝对路径，CI 用这个）；
2. 工程根下 `release-key.jks`（本地开发用这个，属于被忽略文件，不会入库）。

因此：

| 场景 | keystore 位置 | 环境变量来源 |
|------|---------------|--------------|
| 本地开发 | `<工程根>/release-key.jks` | 本机 shell 导出，或直接用 `scripts/generate-keystore.sh` 生成的指引 |
| GitHub Actions | `$RUNNER_TEMP/release-key.jks`（构建结束即随 runner 销毁） | Secrets 里的 base64 解码得到 |

## 2. 首次生成密钥

```bash
# 在工程根执行；默认输出 <工程根>/release-key.jks
bash scripts/generate-keystore.sh

# 自定义路径 / 别名 / 有效期
bash scripts/generate-keystore.sh -o /secure/path/release-key.jks -a daoshu-compass -v 10950
```

脚本使用 `keytool -genkeypair`（RSA 4096、默认有效期 10000 天），并打印 Secrets 配置指引。
口令通过环境变量或交互式静默输入传入，**不会**出现在命令行参数或日志里。

## 3. 配置 GitHub Secrets（4 个）

进入 `Settings → Secrets and variables → Actions → New repository secret`：

| Secret 名称 | 内容 | 说明 |
|-------------|------|------|
| `ANDROID_KEYSTORE_BASE64` | keystore 文件的单行 base64 | 由 `bash scripts/encode-keystore.sh` 生成 |
| `ANDROID_KEY_ALIAS` | 密钥别名 | 例：`daoshu-compass` |
| `ANDROID_KEY_PASSWORD` | key 口令 | 与 keystore 口令不同也可 |
| `ANDROID_STORE_PASSWORD` | keystore 口令 | JKS 的 store 口令 |

生成 base64（建议重定向到临时文件，导入后立即删除）：

```bash
bash scripts/encode-keystore.sh release-key.jks > /tmp/daoshu-key.b64
# 复制 /tmp/daoshu-key.b64 的内容粘贴到 ANDROID_KEYSTORE_BASE64
shred -u /tmp/daoshu-key.b64 2>/dev/null || rm -f /tmp/daoshu-key.b64
```

## 4. 本地签名构建（可选）

```bash
export KEYSTORE_PATH="$PWD/release-key.jks"
export ANDROID_KEY_ALIAS="daoshu-compass"
export ANDROID_KEY_PASSWORD="<key 口令>"
export ANDROID_STORE_PASSWORD="<store 口令>"
./gradlew assembleRelease
bash scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

未设置上述环境变量时，`app/build.gradle.kts` 会自动回退到 debug 签名，保证本地
`assembleRelease` 仍能出包（产物不能用于正式发布）。

## 5. 密钥丢失与轮换

- **密钥丢失 = 无法再对同一 applicationId 发布可覆盖安装的更新**，Google Play 之外的自更新渠道同样受影响，务必异地备份（密码管理器 / 加密离线介质）。
- 轮换密钥后，已安装的旧版本**无法**通过覆盖安装升级（签名不一致），需要用户卸载重装，因此请避免无必要轮换。
- 怀疑泄露时的处置：立即生成新密钥 → 更新 4 个 Secrets → 提升 `versionCode` 并发布 → 在 Release 说明中提示用户卸载重装。

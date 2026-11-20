# Releasing FKUB

**English** · [中文](#中文)

The rolling `latest` build is a **debug** build signed with the public key in `app/fkub-debug.keystore`. Anyone can sign an APK with that key, so it is for testing only.

Public releases are signed with a **private release key** that only the maintainer holds. CI reads it from GitHub Actions secrets when you push a tag such as `v1.0.0`, builds `:app:assembleRelease` and publishes a versioned (non-prerelease) GitHub Release with `FKUB-v1.0.0.apk`.

## One-time setup

1. **Create the release key on your own computer** (needs a JDK, or use Android Studio → Build → Generate Signed App Bundle / APK → Create new):

   ```bash
   keytool -genkeypair -v -keystore fkub-release.jks -alias fkub -keyalg RSA -keysize 4096 -validity 10000
   ```

   Pick strong passwords and write them down.

2. **Back it up.** Keep `fkub-release.jks` and both passwords somewhere safe (a password manager plus an offline copy). If you lose them, you can never publish an update that installs over existing installs. Never commit the file.

3. **Add four repository secrets** (GitHub → the repo → Settings → Secrets and variables → Actions → New repository secret):

   | Secret | Value |
   |---|---|
   | `FKUB_KEYSTORE_BASE64` | the keystore file as base64 (see below) |
   | `FKUB_KEYSTORE_PASSWORD` | keystore password |
   | `FKUB_KEY_ALIAS` | `fkub` (or the alias you chose) |
   | `FKUB_KEY_PASSWORD` | key password |

   Base64 of the file:
   - macOS: `base64 -i fkub-release.jks | pbcopy`
   - Linux: `base64 -w0 fkub-release.jks`
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("fkub-release.jks")) | Set-Clipboard`

## Each release

```bash
git tag v1.0.0
git push origin v1.0.0
```

CI builds, signs and publishes **FKUB v1.0.0**. If the secrets are missing the job fails with a clear error and nothing is published.

Phones that have the debug build must uninstall it once before installing a release build (different signing keys). After that, every release installs over the previous one.

---

## 中文

滚动更新的 `latest` 是**调试版**，用仓库里公开的 `app/fkub-debug.keystore` 签名。任何人都能用这个签名，所以它只用于测试。

正式版用只有维护者持有的**发布签名**。推送 `v1.0.0` 这样的标签时，CI 从 GitHub Actions secrets 读取签名，构建 `:app:assembleRelease`，并发布带版本号的正式 Release（`FKUB-v1.0.0.apk`）。

### 一次性设置

1. **在你自己的电脑上生成发布签名**（需要 JDK，也可以用 Android Studio → Build → Generate Signed App Bundle / APK → Create new）：命令见上面英文部分。密码要设强一点并记下来。
2. **备份。** 把 `fkub-release.jks` 和两个密码放在安全的地方（密码管理器加一份离线备份）。丢了就再也无法发布能覆盖安装的更新。绝对不要提交到仓库。
3. **添加四个仓库 secret**（GitHub → 仓库 → Settings → Secrets and variables → Actions → New repository secret）：`FKUB_KEYSTORE_BASE64`（签名文件的 base64，命令见上）、`FKUB_KEYSTORE_PASSWORD`、`FKUB_KEY_ALIAS`（`fkub`）、`FKUB_KEY_PASSWORD`。

### 每次发布

推送标签 `v1.0.0`（命令见上），CI 会自动构建、签名并发布。secret 缺失时会报错，不会发布任何东西。

装过调试版的手机，第一次装正式版前要先卸载一次（签名不同）。之后每个正式版都能直接覆盖安装。

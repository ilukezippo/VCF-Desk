# Releasing the Android app

The signing key is created on **your own PC** and never goes into the repository or to anyone else. GitHub only gets it as encrypted secrets.

## 1. Create the release key (once)

Use any JDK's `keytool`. Android Studio ships one, in the `jbr\bin` folder of its install, for example:

```
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore vcfdesk-release.jks -alias vcfdesk -keyalg RSA -keysize 4096 -validity 36500
```

(If `keytool` is on your PATH, you can just type `keytool`.) It asks for a keystore password, your name details (anything is fine) and a key password.

**Keep `vcfdesk-release.jks` and both passwords somewhere safe** (a password manager plus a backup copy). If you lose them you can't update the app: Android only accepts updates signed with the same key.

## 2. Add the four GitHub secrets

In PowerShell, in the folder with the key, copy the key as base64:

```
[Convert]::ToBase64String([IO.File]::ReadAllBytes("vcfdesk-release.jks")) | Set-Clipboard
```

On GitHub open the repo -> **Settings -> Secrets and variables -> Actions -> New repository secret** and add:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | the base64 text you just copied |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `vcfdesk` |
| `ANDROID_KEY_PASSWORD` | the key password |

## 3. Publish

The Windows workflow creates the `v1.0` release. Once it exists:

1. GitHub -> **Actions -> Build Android app -> Run workflow**. Leave "Use workflow from" on the branch `main`, type `v1.0` in the **tag** box, and run it. (The `v1.0` tag is older than the Android workflow, so it can't be picked in "Use workflow from"; the app is built from `main` and attached to the tag's release.)
2. It tests, builds the signed APK and attaches `VCF-Desk-Android-1.0.apk` to the existing release. Other release files are not touched. If the secrets are missing the run only warns and skips the upload; if the release doesn't exist yet it stops without creating one.
3. Check that the download link in the README works.

For a later version, raise `versionCode` and `versionName` in `android/app/build.gradle.kts`, wait for the matching release to exist, and run the workflow from `main` with that tag in the **tag** box (or push a new tag, which runs it automatically).

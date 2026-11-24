# Google sign-in setup (about 10 minutes)

Phone contacts work without any of this. You only need it to edit your **Google contacts** from VCF Desk for Android. The app has no Google client ID inside it: Google recognises it by its package name plus the SHA-1 fingerprint of the key that signed it.

You need: a Google account, a computer with a browser, and the SHA-1 of your release key (see [RELEASE.md](RELEASE.md)).

## 1. Create a project and turn on the People API

1. Open <https://console.cloud.google.com/> and sign in.
2. Project picker (top bar) -> **New project**. Name it `VCF Desk` and create it.
3. **APIs & Services -> Library**, search **People API**, open it and press **Enable**.

## 2. Consent screen (Testing mode)

1. **APIs & Services -> OAuth consent screen** (or *Google Auth Platform -> Branding*).
2. User type: **External**. App name `VCF Desk`, your email as support and developer contact.
3. Add the scope `https://www.googleapis.com/auth/contacts` (Data Access -> Add scopes).
4. Leave the publishing status on **Testing**.
5. **Audience -> Test users -> Add users**: add every Google account that will sign in (your own included).

Testing mode allows up to **100 test users**. Anyone else cannot sign in. Opening the app to the public needs Google's verification of the app, because the contacts scope is sensitive.

## 3. Create the Android OAuth client

1. **APIs & Services -> Credentials -> Create credentials -> OAuth client ID**.
2. Application type: **Android**.
3. Package name: `com.ilukezippo.vcfdesk`
4. SHA-1 certificate fingerprint: the **release** key's SHA-1:

   ```
   keytool -list -v -keystore vcfdesk-release.jks -alias vcfdesk
   ```

   Copy the line that starts with `SHA1:`.
5. Create. Nothing needs to be copied into the app.

## 4. Optional: a second client for debug builds

If you also run builds from Android Studio, create another Android client with the same package name and the **debug** key's SHA-1:

```
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
```

## 5. Sign in

Open the app, choose **Google** as the source and pick an account that is on the test-user list. It can take a few minutes after creating a client before Google accepts it.

## Good to know

- Google sign-in uses the **accounts on the phone**. Signing in with an account that is not on the phone adds it to the phone, and Android may start syncing that account's contacts, mail and so on. You can turn that off in **Settings -> Passwords and accounts -> (the account) -> Account sync**.
- If sign-in says the app isn't verified, you are using an account that isn't in the test-user list.
- If sign-in fails with "developer error", the package name or SHA-1 of the client doesn't match the key that signed the installed APK.

# VCF Desk for Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native Android "VCF Desk – Contacts Manager" that browses, edits and bulk-edits contacts on the phone and in signed-in Google accounts, with VCF/Excel/CSV import and export, previews, automatic backups and undo.

**Architecture:**
- A pure-Kotlin `:core` module (JVM, fast unit tests) holds the contact model, the vCard/CSV/XLSX formats, the name/phone tools, the bulk-edit rules and duplicate finding. It is ported from the desktop `vcf_core.py`.
- The `:app` module (Jetpack Compose, Material 3 adaptive) holds two `ContactSource` implementations:
  - **Device**, over Android's `ContactsContract`, working on raw contacts so that each account's copy is its own row.
  - **Google**, over the People API REST calls with a token from Google Identity Services.
- A `BulkRunner` sits on top of both sources: preview, then backup, then batched apply, then undo.

**Tech Stack:**
- Kotlin 2.x, AGP 8.x, Gradle 8.14 wrapper.
- Jetpack Compose with the Material 3 BOM, plus `material3-adaptive` (list-detail).
- Coroutines and Flow.
- OkHttp 4 and kotlinx.serialization for the People API.
- `com.google.android.gms:play-services-auth:21.6.0` (AuthorizationClient).
- JUnit 4, MockWebServer, Turbine.

**Spec:** `docs/superpowers/specs/2026-11-23-vcf-desk-android-design.md`

## Global Constraints

- Everything Android lives under `android/` in the existing repo `ilukezippo/VCF-Desk`. The Windows app files in the repo root are not touched, except the README, PRIVACY.md, CHANGELOG.md and `.github/workflows/`.
- `applicationId` and package: `com.ilukezippo.vcfdesk`. `versionName "1.0"`, `versionCode 1`. App label "VCF Desk", subtitle "Contacts Manager".
- `minSdk 26`, `compileSdk 36`, `targetSdk 36`. The manifest has `android:supportsRtl="true"`.
- English UI strings, all in `res/values/strings.xml`. Arabic content must display right to left (use `TextDirection.Content` on text fields and list text).
- Permissions: `READ_CONTACTS`, `WRITE_CONTACTS`, `GET_ACCOUNTS`, `INTERNET`. The device source never touches the network.
- Google: `play-services-auth` AuthorizationClient, scope `https://www.googleapis.com/auth/contacts`. No client ID in code: the Android OAuth client is matched by package name plus signing SHA-1.
- People API limits: batchCreate and batchUpdate at most 200 per call, batchDelete at most 500 per call. Every update sends the person's `etag`.
- Device writes: `applyBatch` chunks of at most 400 operations, with `withYieldAllowed(true)` on the first operation of each contact.
- Release signing: the keystore is never committed. CI reads it from the secrets `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`.
- Release asset name: `VCF-Desk-Android-1.0.apk`, attached to the existing `v1.0` release.
- Demo and screenshot data: the word "Name" (Name 1, Name 2…) and numbers like 55555555, 99999999 and 66666666 only.
- Default country code `+965` for 8-digit local numbers. Both are editable in Settings.

## Review Focus

1. **Contacts carrying data the app doesn't model** (WhatsApp/Signal rows, SIP, custom mimetypes, Google fields such as `externalIds`). After any edit, those must be byte-for-byte untouched. Tests: Task 6 (`keepsUnknownMimetypeRows`) and Task 7 (`updateMaskOnlyNamesEditedFields`).
2. **Arabic everywhere:**
   - quoted-printable Arabic from old Android VCFs;
   - names like عبد الله staying whole when split;
   - mixed Arabic and English;
   - Arabic-heavy Excel exports opening right to left.
   
   Tests: Task 1 (`androidQuotedPrintableArabic`), Task 2 (`splitKeepsAbdAllah`) and Task 4 (`excelRtlWhenMostlyArabic`).
3. **Read-only raw contacts** (SIM, WhatsApp, other sync adapters that refuse writes). A bulk edit skips them and reports "N skipped (read-only)" instead of failing the batch. Tests: Task 6 (`readOnlyAccountsAreSkipped`) and Task 8 (`skippedAreReported`).
4. **Partial failure mid-bulk:** an etag conflict (HTTP 400 "FAILED_PRECONDITION" or 412), losing the network, or a revoked permission. Contacts already written stay written, the rest are reported, and the backup can restore them. Tests: Task 7 (`etagConflictReportedPerContact`) and Task 8 (`partialFailureReportsAndUndoRestores`).
5. **Big address books:** 5,000 contacts with photos must page in without loading every full-size photo; the list uses thumbnails only. Tests: Task 6 (`loadsThumbnailsNotDisplayPhotos`) and Task 7 (`pagesThroughConnections`).

---

## File Structure

```
android/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew(+bat), gradle/wrapper/*
  core/                                  pure Kotlin (JVM) module
    src/main/kotlin/com/ilukezippo/vcfdesk/core/
      model/Contact.kt                   Contact + typed field classes + Extra (unknown data)
      vcard/VCardReader.kt               parse .vcf → List<Contact>
      vcard/VCardWriter.kt               Contact → vCard 2.1/3.0/4.0 text
      vcard/QuotedPrintable.kt
      text/Names.kt                      compose/split names, first middle only, Arabic detection
      text/Phones.kt                     normalisePhone, addCountryCode
      text/Dates.kt                      normaliseDate
      bulk/BulkChange.kt                 sealed BulkChange + apply + preview diff
      tidy/Duplicates.kt                 exact-duplicate groups, pick most complete
      table/Csv.kt                       read/write CSV (BOM, multi-line cells)
      table/Xlsx.kt                      read sheets / write .xlsx (zip+XML, RTL flag)
      table/ColumnGuess.kt               header → Field (English + Arabic)
      table/RowImport.kt                 rows + mapping → contacts
      export/Exporters.kt                ExportFormat enum + export(contacts, format) → files
    src/test/kotlin/...                  one test file per main file
    src/test/resources/demo.vcf          copied from tests/data/demo.vcf
  app/
    src/main/AndroidManifest.xml
    src/main/kotlin/com/ilukezippo/vcfdesk/
      VcfDeskApp.kt, MainActivity.kt
      data/ContactSource.kt              interface + SourceId + WriteResult
      data/device/DeviceMapper.kt        ContactsContract rows ↔ Contact (pure)
      data/device/DeviceSource.kt        ContentResolver queries + applyBatch
      data/google/PeopleMapper.kt        Person JSON ↔ Contact (pure)
      data/google/PeopleApi.kt           OkHttp client for People API
      data/google/GoogleAuth.kt          account pick + AuthorizationClient token
      data/google/GoogleSource.kt
      data/backup/BackupStore.kt         save/list/load VCF backups in filesDir/backups
      data/BulkRunner.kt                 preview → backup → apply → report; undo
      data/Settings.kt                   DataStore: country code, google accounts
      ui/theme/*, ui/AppScaffold.kt      source switcher + list-detail layout
      ui/list/ContactListScreen.kt + ContactListViewModel.kt
      ui/editor/ContactEditorScreen.kt + ContactEditorViewModel.kt
      ui/bulk/BulkEditSheet.kt + BulkPreviewDialog.kt + BulkViewModel.kt
      ui/io/ImportScreen.kt, ExportScreen.kt, IoViewModel.kt
      ui/backups/BackupsScreen.kt, ui/settings/SettingsScreen.kt
    src/test/kotlin/...                  JVM tests for mappers, PeopleApi, BulkRunner, view models
.github/workflows/android.yml
docs/android/GOOGLE_SETUP.md
```

---

### Task 0: Repo docs and Gradle skeleton with CI

**Files:**
- Create:
  - `docs/superpowers/specs/2026-11-23-vcf-desk-android-design.md` (already copied)
  - this plan
  - `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, the wrapper files
  - `android/core/build.gradle.kts`
  - `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`
  - `MainActivity.kt` (shows "VCF Desk – Contacts Manager"), `VcfDeskApp.kt`
  - launcher icons generated from `assets/vcfdesk.png`
  - `.github/workflows/android.yml`
- Modify: `.github/workflows/build.yml`, adding `"android/**"` to `paths-ignore` so Android changes don't rebuild Windows.

**Interfaces:**
- Produces:
  - Gradle modules `:core` (kotlin-jvm, JVM toolchain 17) and `:app` (depends on `:core`).
  - The workflow `android.yml` with `on.push.paths: ["android/**", ".github/workflows/android.yml"]`, `on.push.tags: ["v*"]` and `workflow_dispatch`.
  - Its jobs:
    1. `./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease`
    2. When the four signing secrets exist, sign with them; otherwise build an unsigned debug APK as an artifact only.
    3. On a tag, rename the APK to `VCF-Desk-Android-<versionName>.apk` and upload it with `softprops/action-gh-release@v2` (`files:` set to the APK, without replacing other assets).

- [ ] **Step 1:** Install the Android SDK locally:
  - cmdline-tools, then `platforms;android-36`, `build-tools;36.0.0` and `platform-tools`;
  - set `ANDROID_HOME`;
  - create the wrapper with `/opt/gradle/bin/gradle wrapper --gradle-version 8.14.3` in `android/`.
- [ ] **Step 2:** Write the skeleton files above. In the manifest, the `<application>` has label `@string/app_name` = "VCF Desk", `supportsRtl=true`, and the four permissions.
- [ ] **Step 3:** Run `cd android && ./gradlew :core:test :app:assembleDebug`. Expected: BUILD SUCCESSFUL, and `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 4:** Commit: `git add android docs .github && git commit -m "android: project skeleton, design and plan"`.

### Task 1: Contact model and vCard read/write

**Files:**
- Create: `core/.../model/Contact.kt`, `vcard/VCardReader.kt`, `vcard/VCardWriter.kt`, `vcard/QuotedPrintable.kt`
- Test: `core/src/test/.../vcard/VCardTest.kt`, plus `src/test/resources/demo.vcf` copied from `tests/data/demo.vcf`

**Interfaces:**
- Produces:

```kotlin
data class Contact(
  val id: String = "",                 // source key: raw contact id, People resourceName, or "" for file
  val account: AccountTag? = null,     // AccountTag(type: String, name: String, label: String, readOnly: Boolean)
  val etag: String? = null,
  val name: Name = Name(),             // prefix, first, middle, last, suffix, phoneticFirst, phoneticLast, nickname, display
  val phones: List<Typed> = emptyList(),      // Typed(label: String, value: String)
  val emails: List<Typed> = emptyList(),
  val addresses: List<Address> = emptyList(), // label, street, city, region, postcode, country
  val org: Org = Org(),                // company, department, title
  val websites: List<Typed> = emptyList(),
  val events: List<Typed> = emptyList(),      // label "birthday" or custom; value yyyy-MM-dd or --MM-dd
  val relations: List<Typed> = emptyList(),
  val ims: List<Typed> = emptyList(),
  val notes: String = "",
  val groups: Set<String> = emptySet(),
  val photo: ByteArray? = null,        // full image when loaded; null = unchanged/unknown
  val thumbnail: ByteArray? = null,
  val extras: List<Extra> = emptyList(),      // anything not modelled, kept verbatim per source
)
object VCardReader { fun read(text: String): List<Contact> }
enum class VCardVersion { V21, V30, V40 }
object VCardWriter { fun write(contacts: List<Contact>, version: VCardVersion): String }
```

For vCard, `Extra` holds a raw property line, which the writer re-emits verbatim.

- [ ] **Step 1:** Write the failing tests, ported from `tests/test_core.py`:
  - `androidQuotedPrintableArabic`: the QP-encoded Arabic FN from `demo.vcf` reads as the exact Arabic string.
  - `iphoneItemLabels`: `item1.TEL` + `item1.X-ABLabel:Work Mobile` gives a phone labelled "Work Mobile".
  - `unknownPropertiesSurvive`: an `X-CUSTOM:abc` line comes out unchanged after read → write.
  - `roundTripEveryVersion`: read `demo.vcf` → write in V21/V30/V40 → read again gives the same names, phones, org and photo bytes.
  - `foldedLinesAndEscapes`: a 75-column folded NOTE with `\,` `\;` `\n` reads back correctly.
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*VCardTest*'`. Expected: FAIL (classes missing).
- [ ] **Step 3:** Implement by porting `vcf_core.py` functions: `parse_vcf`, `_logical_lines`, `_make_prop`, `_qp_encode`, `_prop_line`, `serialize_contact`, `_fold`. Keep the same behaviour, including V21 using QP for non-ASCII and BASE64 PHOTO with TYPE.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit: `core: contact model and vCard read/write`.

### Task 2: Name, phone and date tools, and bulk-edit rules

**Files:**
- Create: `core/.../text/Names.kt`, `text/Phones.kt`, `text/Dates.kt`, `bulk/BulkChange.kt`
- Test: `NamesTest.kt`, `PhonesTest.kt`, `BulkChangeTest.kt`

**Interfaces:**
- Consumes: `Contact` (Task 1).
- Produces:

```kotlin
object Names {
  fun compose(n: Name): String
  fun split(full: String, oneMiddle: Boolean = false): Name
  fun firstMiddleOnly(middle: String): String
  fun isMostlyArabic(texts: List<String>): Boolean
}
object Phones {
  fun normalise(v: String, cc: String): String
  fun addCountryCode(v: String, cc: String = "+965", localLength: Int = 8): String
}
object Dates { fun normalise(v: String): String }  // → yyyy-MM-dd, or --MM-dd when there's no year

enum class TextField { COMPANY, DEPARTMENT, TITLE, NOTES, CITY, COUNTRY, PREFIX, SUFFIX, FIRST, MIDDLE, LAST, NICKNAME }
sealed interface BulkChange {
  data class SetField(val field: TextField, val value: String) : BulkChange   // "" clears
  data class AppendNotes(val text: String) : BulkChange
  data class FindReplace(val field: TextField, val find: String, val replace: String, val ignoreCase: Boolean = true) : BulkChange
  data class AddGroup(val group: String) : BulkChange
  data class RemoveGroup(val group: String) : BulkChange
  data object FirstMiddleOnly : BulkChange
  data class AddCountryCode(val code: String = "+965", val localLength: Int = 8) : BulkChange
}
data class FieldDiff(val label: String, val before: String, val after: String)
data class ContactDiff(val contact: Contact, val after: Contact, val changes: List<FieldDiff>)
object Bulk {
  fun apply(c: Contact, changes: List<BulkChange>): Contact
  fun preview(cs: List<Contact>, changes: List<BulkChange>): List<ContactDiff>  // only contacts that actually change
}
```

Notes on `BulkChange`:
- `CITY` and `COUNTRY` apply to every address, and create one "home" address if there is none.
- Delete and copy/move are not `BulkChange` values. They are `BulkRunner` operations (Task 8).

- [ ] **Step 1:** Write the failing tests:
  - `splitKeepsAbdAllah`: `split("محمد عبد الله أحمد")` gives first "محمد", middle "عبد الله", last "أحمد".
  - `oneMiddleKeepsFirstMiddleOnly`: `split("A B C D", oneMiddle = true)` gives middle "B", last "D".
  - `firstMiddleOnly("عبد الله سالم") == "عبد الله"`.
  - `addCountryCode`: "55555555" → "+96555555555"; "+96599999999" unchanged; "1234" unchanged.
  - `normaliseDates`: "1/2/1990" → "1990-02-01" (day first, as on desktop); "1990-02-01" unchanged.
  - `previewSkipsUnchanged`: `SetField(COMPANY,"X")` over 3 contacts, one of which already has X, gives 2 diffs, each with `FieldDiff("Company", old, "X")`.
  - `cityCreatesAddressWhenMissing`.
  - `findReplaceInTitle`.
  - `addRemoveGroup`.
- [ ] **Step 2:** Run `./gradlew :core:test`. Expected: the new tests FAIL.
- [ ] **Step 3:** Implement by porting `compose_name`, `name_words`, `first_middle`, `split_full_name`, `normalize_phone`, `normalize_date` and `bulk_apply` from `vcf_core.py`. Arabic multi-word name parts follow `name_words`: "عبد" and "أبو" join the next word.
- [ ] **Step 4:** Run `./gradlew :core:test`. Expected: PASS.
- [ ] **Step 5:** Commit: `core: names, phones, dates and bulk rules`.

### Task 3: Exact duplicates

**Files:**
- Create: `core/.../tidy/Duplicates.kt`
- Test: `DuplicatesTest.kt`

**Interfaces:**
- Produces: `object Duplicates { fun groups(cs: List<Contact>): List<List<Contact>>; fun keeper(group: List<Contact>): Contact }`
- A duplicate key is: the lower-cased trimmed display name plus the sorted set of phones normalised with `Phones.normalise(v, "")`, plus the sorted emails. This matches `duplicate_key` on desktop.
- `keeper` picks the contact with the most non-empty fields; a photo counts as 3.

- [ ] **Step 1:** Write the failing tests:
  - `sameNameSameNumbersIsDuplicate`
  - `differentAccountsStillGroupOnlyWithinSameAccount`: groups never mix two `AccountTag`s.
  - `keeperPrefersPhoto`
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*Duplicates*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit: `core: exact duplicate finding`.

### Task 4: Tables (CSV/XLSX), column guessing, import and export

**Files:**
- Create: `core/.../table/Csv.kt`, `Xlsx.kt`, `ColumnGuess.kt`, `RowImport.kt`, `export/Exporters.kt`
- Test: `CsvTest.kt`, `XlsxTest.kt`, `ColumnGuessTest.kt`, `ExportersTest.kt`

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces:

```kotlin
data class Table(val headers: List<String>, val rows: List<List<String>>)
object Csv { fun read(bytes: ByteArray): Table; fun write(t: Table): ByteArray }   // UTF-8 with BOM; cells may contain line breaks
object Xlsx { fun sheets(bytes: ByteArray): Map<String, Table>; fun write(t: Table, sheet: String = "Contacts", rtl: Boolean = false): ByteArray }
enum class Field { SKIP, FULL_NAME, PREFIX, FIRST, MIDDLE, LAST, SUFFIX, NICKNAME, MOBILE, PHONE_HOME, PHONE_WORK, EMAIL, COMPANY, DEPARTMENT, TITLE, STREET, CITY, COUNTRY, BIRTHDAY, NOTES, GROUPS, WEBSITE, PHOTO }
object ColumnGuess { fun guess(header: String): Field }
object RowImport { fun toContacts(t: Table, mapping: List<Field>, cc: String = "", oneMiddle: Boolean = false): List<Contact> }
enum class ExportFormat(val ext: String) { ANDROID_VCF("vcf"), IPHONE_VCF("vcf"), VCARD4("vcf"), GOOGLE_CSV("csv"), OUTLOOK_CSV("csv"), EXCEL("xlsx"), CSV("csv"), SPLIT_VCF_ZIP("zip") }
object Exporters { fun export(cs: List<Contact>, f: ExportFormat): ByteArray }
```

`PHOTO` column cells hold base64 or a file name. File names are resolved by the app layer, which passes the bytes in through `Contact.photo`. On Android there are no photo folders.

- [ ] **Step 1:** Write the failing tests, ported from `test_core.py`:
  - `googleCsvHasBom`
  - `googleAndOutlookCsvReimport`: export, then `Csv.read`, then guess, then `RowImport` gives the same names and phones.
  - `excelRoundTrip`
  - `csvCellsWithLineBreaks`
  - `columnGuessing`: "Mobile", "الجوال" and "رقم الهاتف" → MOBILE; "الاسم" → FULL_NAME; "Department"/"القسم" → DEPARTMENT; "#" → SKIP.
  - `excelRtlWhenMostlyArabic`: the sheet XML contains `rightToLeft="1"` when names are mostly Arabic.
  - `splitZipHasOneFilePerContact`: zip entries are named by display name and made unique.
- [ ] **Step 2:** Run `./gradlew :core:test`. Expected: the new tests FAIL.
- [ ] **Step 3:** Implement by porting:
  - `csv_rows`, `xlsx_sheets`, `_xlsx_cell_value`, `write_xlsx`;
  - `guess_field` (same English + Arabic keyword table);
  - `rows_to_contacts`, `write_google_csv`, `write_outlook_csv`, `write_excel`, `write_csv_table`, `write_split`.

  XLSX uses `java.util.zip` plus hand-written XML, with no POI.
- [ ] **Step 4:** Run `./gradlew :core:test`. Expected: PASS.
- [ ] **Step 5:** Commit: `core: CSV/XLSX import and all export formats`.

### Task 5: ContactSource contract and Settings

**Files:**
- Create: `app/.../data/ContactSource.kt`, `data/Settings.kt`
- Test: `SettingsTest.kt` (Robolectric or a DataStore test scope)

**Interfaces:**
- Produces:

```kotlin
sealed interface SourceId { data object Device : SourceId; data class Google(val email: String) : SourceId }
data class WriteResult(val written: List<String>, val skippedReadOnly: List<String>, val failed: Map<String, String>) // ids → reason
interface ContactSource {
  val id: SourceId
  fun contacts(): Flow<List<Contact>>                 // thumbnails only, no full photos
  suspend fun load(id: String): Contact               // full contact incl. full photo
  suspend fun groups(): List<String>
  suspend fun update(cs: List<Contact>, onProgress: (Int) -> Unit = {}): WriteResult
  suspend fun create(cs: List<Contact>, target: AccountTag?, onProgress: (Int) -> Unit = {}): WriteResult
  suspend fun delete(ids: List<String>, onProgress: (Int) -> Unit = {}): WriteResult
  suspend fun refresh()
}
class Settings(context: Context) { val countryCode: Flow<String>; val googleAccounts: Flow<List<String>>; suspend fun setCountryCode(v: String); suspend fun addGoogleAccount(email: String); suspend fun removeGoogleAccount(email: String) }
```

`countryCode` defaults to "+965".

- [ ] **Step 1:** Write the failing test `defaultsAndPersistence`: the default country code is "+965", and an added account comes back from the flow.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*SettingsTest*'`. Expected: FAIL.
- [ ] **Step 3:** Implement `Settings` with Preferences DataStore.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit: `app: source contract and settings`.

### Task 6: Device source (ContactsContract)

**Files:**
- Create: `app/.../data/device/DeviceMapper.kt`, `DeviceSource.kt`
- Test: `DeviceMapperTest.kt`, `DeviceSourceTest.kt` (Robolectric with a fake `ContentProvider` registered for `ContactsContract.AUTHORITY`)

**Interfaces:**
- Consumes: `Contact`, `ContactSource`, `WriteResult`.
- Produces:

```kotlin
data class DataRow(val id: Long?, val mimetype: String, val values: Map<String, String?>, val blob: ByteArray? = null)
object DeviceMapper {
  fun toContact(rawId: Long, account: AccountTag, rows: List<DataRow>, groupTitles: Map<Long, String>): Contact
  fun diff(before: List<DataRow>, after: Contact, groupIds: Map<String, Long>): List<RowOp>   // RowOp = Insert(DataRow) | Update(id, values, blob) | Delete(id)
}
class DeviceSource(context: Context) : ContactSource
```

How it works:
- Works on `RawContacts` (not aggregated contacts), each with its `ACCOUNT_TYPE`/`ACCOUNT_NAME`. `AccountTag.label` is mapped: `com.google` → "Google", `com.osp.app.signin` → "Samsung", `null` or local types → "Phone", and anything else uses the account type's label from AccountManager.
- `readOnly` comes from the `SyncAdapterType.supportsUploading()` of that account type. Local and phone accounts are writable.
- Only these mimetypes are mapped:
  - StructuredName, Phone, Email, StructuredPostal, Organization, Website, Event, Relation, Im, Note, Nickname, GroupMembership, Photo.
  - Every other row goes into `extras` and is never in any `RowOp`.
- The list query reads `Photo.PHOTO` (thumbnail) only. `load(id)` also reads the display photo through `openAssetFileDescriptor(DisplayPhoto)`.
- `update` turns the diff into `ContentProviderOperation`s, chunked at no more than 400 per `applyBatch`. Raw contacts in a read-only account go to `skippedReadOnly` without any operations.
- `create(target)` inserts a RawContact with the target account (null means the phone's local account), then the data rows.
- `delete` deletes the RawContact with `CALLER_IS_SYNCADAPTER` unset, so deletes sync.

- [ ] **Step 1:** Write the failing tests:
  - `mapsAllModelledMimetypes`
  - `keepsUnknownMimetypeRows`: a `vnd.android.cursor.item/vnd.com.whatsapp.profile` row is in `extras`, and `diff` never touches its id.
  - `diffUpdatesOnlyChangedRows`: changing the company gives exactly one `Update` on the Organization row.
  - `groupMembershipDiff`: adding a group inserts a GroupMembership row with the group id.
  - `readOnlyAccountsAreSkipped`: `update` on a read-only raw contact gives `skippedReadOnly` = [id] and no provider calls.
  - `batchesAt400Ops`: 250 contacts × 2 ops each produces 2 `applyBatch` calls.
  - `loadsThumbnailsNotDisplayPhotos`: `contacts()` never opens a DisplayPhoto URI.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*Device*'`. Expected: FAIL.
- [ ] **Step 3:** Implement `DeviceMapper`, then `DeviceSource`, with `contacts()` as a Flow re-queried on a `ContentObserver` for `RawContacts.CONTENT_URI`.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit: `app: device contacts source`.

### Task 7: Google source (People API)

**Files:**
- Create: `app/.../data/google/PeopleMapper.kt`, `PeopleApi.kt`, `GoogleAuth.kt`, `GoogleSource.kt`
- Test: `PeopleMapperTest.kt`, `PeopleApiTest.kt` (MockWebServer)

**Interfaces:**
- Consumes: `Contact`, `ContactSource`, `WriteResult`.
- Produces:

```kotlin
object PeopleMapper {
  fun toContact(person: JsonObject, groupNames: Map<String, String>): Contact     // resourceName → id, etag kept, unknown keys → extras (raw JSON)
  fun toPerson(c: Contact, groupIds: Map<String, String>): JsonObject
  fun updateMask(before: Contact, after: Contact): String                          // e.g. "organizations,memberships"
}
class PeopleApi(http: OkHttpClient, token: suspend () -> String, base: String = "https://people.googleapis.com/v1/") {
  suspend fun listConnections(pageToken: String?): Pair<List<JsonObject>, String?>   // pageSize=1000, personFields = PERSON_FIELDS
  suspend fun contactGroups(): List<JsonObject>
  suspend fun batchUpdate(people: Map<String, JsonObject>, updateMask: String): Map<String, Result<JsonObject>>  // ≤200
  suspend fun batchCreate(people: List<JsonObject>): List<Result<JsonObject>>                                      // ≤200
  suspend fun batchDelete(resourceNames: List<String>)                                                             // ≤500
  suspend fun updatePhoto(resourceName: String, jpeg: ByteArray)
  suspend fun modifyGroupMembers(groupResource: String, add: List<String>, remove: List<String>)
}
const val PERSON_FIELDS = "names,nicknames,phoneNumbers,emailAddresses,addresses,organizations,urls,birthdays,events,relations,imClients,biographies,memberships,photos,metadata"
class GoogleAuth(activity: ComponentActivity) {
  fun pickAccountIntent(): Intent                         // AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
  suspend fun token(email: String): String                // AuthorizationClient.authorize with setAccount(Account(email,"com.google")) + contacts scope; launches the PendingIntent consent when needed
}
class GoogleSource(email: String, api: PeopleApi) : ContactSource
```

Notes:
- `PeopleMapper` keeps each list entry's `metadata` and unknown keys so that updates don't drop them.
- Only fields that differ go in `updateMask`, so Google keeps everything else.
- Groups map through `memberships.contactGroupMembership.contactGroupResourceName` ↔ group names from `contactGroups()`. A bulk "add group" on a missing group creates it first (`POST contactGroups`).

- [ ] **Step 1:** Write the failing tests:
  - `mapsPersonFields`
  - `updateMaskOnlyNamesEditedFields`: changing the job title gives `"organizations"`.
  - `keepsUnknownPersonKeys`
  - `pagesThroughConnections`: 3 pages are followed until there's no `nextPageToken`.
  - `batchUpdateSplitsAt200`: 450 updates make 3 requests.
  - `etagConflictReportedPerContact`: one entry with a 400 FAILED_PRECONDITION goes into `failed`, and the others into `written`.
  - `sendsBearerToken`
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*People*'`. Expected: FAIL.
- [ ] **Step 3:** Implement. `GoogleSource.update` diffs against the cached `before` to build the mask, then calls `updatePhoto` separately when the photo changed. `GoogleAuth` has no unit test; it is exercised in Task 12's manual check.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit: `app: Google People API source`.

### Task 8: Backups and BulkRunner

**Files:**
- Create: `app/.../data/backup/BackupStore.kt`, `data/BulkRunner.kt`
- Test: `BulkRunnerTest.kt`, with a `FakeSource : ContactSource` in test sources

**Interfaces:**
- Consumes: `Bulk.preview`, `VCardWriter`/`VCardReader`, `ContactSource`.
- Produces:

```kotlin
data class Backup(val file: File, val time: Instant, val action: String, val source: SourceId, val count: Int)
class BackupStore(dir: File) { fun save(cs: List<Contact>, action: String, source: SourceId): Backup; fun list(): List<Backup>; fun load(b: Backup): List<Contact> }
sealed interface BulkOp { data class Edit(val changes: List<BulkChange>) : BulkOp; data object Delete : BulkOp; data class CopyTo(val target: ContactSource, val account: AccountTag?, val move: Boolean) : BulkOp; data object RemoveExactDuplicates : BulkOp }
data class BulkReport(val changed: Int, val skippedReadOnly: Int, val failed: Map<String, String>, val backup: Backup?)
class BulkRunner(private val store: BackupStore) {
  suspend fun preview(src: ContactSource, ids: List<String>, op: BulkOp): List<ContactDiff>
  suspend fun run(src: ContactSource, ids: List<String>, op: BulkOp, progress: (Int, Int) -> Unit): BulkReport
  suspend fun undo(src: ContactSource, backup: Backup): BulkReport
}
```

How it works:
- `run` always calls `load(id)` for the affected contacts first, so the backup has full photos.
- It saves the backup as V30 VCF in `filesDir/backups/yyyyMMdd-HHmmss-<action>.vcf`, with metadata in a sidecar `.json`.
- Then it writes and reports.
- `undo`:
  - Contacts that still exist (same id) are updated back to the backup values.
  - Deleted contacts are re-created in their original account.
  - For Delete and Move, undo re-creates the contacts. Ids change, which is acceptable.

- [ ] **Step 1:** Write the failing tests:
  - `backupWrittenBeforeAnyWrite`: FakeSource records the call order: `load…`, then backup file exists, then `update`.
  - `skippedAreReported`
  - `partialFailureReportsAndUndoRestores`: 3 contacts, the second fails; the report has failed = 1 and changed = 2; `undo` restores the old company on all 3.
  - `deleteUndoRecreates`
  - `moveCopiesThenDeletesOnlyCopied`: when the copy fails for one contact, that contact is not deleted from the source.
  - `removeExactDuplicatesKeepsKeeper`
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*BulkRunner*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit: `app: backups, bulk runner and undo`.

### Task 9: App shell, source switcher and contact list

**Files:**
- Create: `ui/theme/*`, `ui/AppScaffold.kt`, `ui/list/ContactListScreen.kt`, `ContactListViewModel.kt`
- Modify: `MainActivity.kt`
- Test: `ContactListViewModelTest.kt`

**Interfaces:**
- Consumes: `ContactSource`, `Settings`, `GoogleAuth`.
- Produces:

```kotlin
data class ListState(val source: SourceId, val sources: List<SourceId>, val query: String, val accountFilter: String?, val groupFilter: String?, val sortAsc: Boolean, val items: List<Contact>, val selected: Set<String>, val loading: Boolean)
class ContactListViewModel(...) {
  val state: StateFlow<ListState>
  fun setQuery(q: String); fun setAccountFilter(a: String?); fun setGroupFilter(g: String?); fun toggleSort()
  fun toggleSelect(id: String); fun selectAll(); fun selectResults(); fun clearSelection()
  fun switchSource(s: SourceId); fun addGoogleAccount(email: String)
}
```

- Search matches every text field (names, phones digits-only, emails, org, notes, addresses).
- The dark theme matches the desktop app's colours: accent `#7b8cff`, the same as the README badge.
- The top bar shows "VCF Desk" with the subtitle "Contacts Manager" and the source switcher (dropdown: "On this phone", each Google email, "Add Google account…").
- Rows show the thumbnail, the display name (`TextDirection.Content`), the main phone and an account badge.
- The layout is `ListDetailPaneScaffold`: the detail pane is the editor (Task 10) or the bulk sheet (Task 11).
- On first launch the app requests `READ_CONTACTS`/`WRITE_CONTACTS`. If they're denied, it shows an explainer with a retry button.
- When the current source is `SourceId.Google(email)` and that email is also a `com.google` account on the phone with contact sync on (`ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY)`), show the banner "This account is also on your phone; changes show up in both." `ListState` gains `alsoOnPhone: Boolean`.

- [ ] **Step 1:** Write the failing tests:
  - `searchMatchesPhoneDigits`: "5555" finds "+965 5555 5555".
  - `filterByAccountLabel`
  - `selectResultsSelectsOnlyFiltered`
  - `sortArabicAndEnglishCollated`: uses a `Collator` for Locale("ar") and English, and stays stable.
  - `alsoOnPhoneBannerWhenSynced`: a Google source whose email is a synced phone account gives `alsoOnPhone == true`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*ContactList*'`. Expected: FAIL.
- [ ] **Step 3:** Implement the view model and screens.
- [ ] **Step 4:** Run the tests (PASS), then `./gradlew :app:assembleDebug` (BUILD SUCCESSFUL).
- [ ] **Step 5:** Commit: `app: shell, sources and contact list`.

### Task 10: Contact editor

**Files:**
- Create: `ui/editor/ContactEditorScreen.kt`, `ContactEditorViewModel.kt`
- Test: `ContactEditorViewModelTest.kt`

**Interfaces:**
- Consumes: `ContactSource.load/update/create`, `Names.compose`.
- Produces: `class ContactEditorViewModel { val contact: StateFlow<Contact?>; fun edit(transform: (Contact) -> Contact); fun undo(); fun redo(); suspend fun save(): WriteResult; fun setPhoto(bytes: ByteArray) }`

What the screen has:
- Sections for name parts, phones, emails, addresses, organization, websites, events, relations, IM, notes, groups and photo. Every typed list has add/remove and a label dropdown (Mobile/Home/Work/Other/Custom…).
- Photo: pick with the Photo Picker, scale to ≤ 720 px JPEG, and tap to view full size.
- In-editor undo/redo of field edits.
- Save is enabled only when something changed. For read-only accounts, fields are disabled and a banner says "This account is read-only".

- [ ] **Step 1:** Write the failing tests:
  - `undoRedoFieldEdits`
  - `saveOnlyWhenChanged`
  - `photoScaledTo720`
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*Editor*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests (PASS), then `assembleDebug`.
- [ ] **Step 5:** Commit: `app: contact editor`.

### Task 11: Bulk edit sheet, preview, progress and undo

**Files:**
- Create: `ui/bulk/BulkEditSheet.kt`, `BulkPreviewDialog.kt`, `BulkViewModel.kt`
- Test: `BulkViewModelTest.kt`

**Interfaces:**
- Consumes: `BulkRunner`, `BulkOp`, `BulkChange`, `Settings.countryCode`.
- Produces: `class BulkViewModel { val stage: StateFlow<Stage> /* Compose | Preview(diffs) | Running(done,total) | Done(BulkReport) */; fun preview(op: BulkOp); fun confirm(); fun cancel(); fun undoLast() }`

The sheet title is "Edit N contacts". It has:
- "Set field" (field picker plus text, with Replace/Clear and Append for notes).
- "Find & replace".
- "Groups" (add/remove; picker plus new).
- "Keep first middle name".
- "Add country code" (prefilled from settings).
- "Copy / move to…" (target source plus account).
- "Remove exact duplicates".
- "Delete" (red; its confirm dialog states the count).

The preview shows "Company → X on 214 contacts", the list of affected names, and a before/after for a tapped one. Done shows the counts, "N skipped (read-only)", any failures, and an "Undo" button.

- [ ] **Step 1:** Write the failing tests:
  - `previewThenConfirmRuns`
  - `cancelStopsBetweenBatches`
  - `undoLastUsesReportBackup`
  - `deleteNeedsSecondConfirm`
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*BulkViewModel*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests (PASS), then `assembleDebug`.
- [ ] **Step 5:** Commit: `app: bulk edit with preview and undo`.

### Task 12: Import, export, backups and settings screens

**Files:**
- Create: `ui/io/ImportScreen.kt`, `ExportScreen.kt`, `IoViewModel.kt`, `ui/backups/BackupsScreen.kt`, `ui/settings/SettingsScreen.kt`
- Modify: `AndroidManifest.xml`, adding an intent-filter for `text/x-vcard`, `text/vcard`, CSV and XLSX `ACTION_VIEW`/`ACTION_SEND` so that "Open with VCF Desk" goes to Import.
- Test: `IoViewModelTest.kt`

**Interfaces:**
- Consumes: `Csv`, `Xlsx`, `ColumnGuess`, `RowImport`, `VCardReader`, `Exporters`, `ContactSource.create`, `BackupStore`.
- Produces: `class IoViewModel { fun open(uri: Uri); val mapping: StateFlow<List<Field>>; fun setMapping(i: Int, f: Field); fun importInto(target: ContactSource, account: AccountTag?); fun export(scope: ExportScope /* ALL | RESULTS | SELECTION */, f: ExportFormat, dest: Uri) }`

- **Import:**
  1. SAF picker.
  2. For VCF, a preview count. For tables: a sheet picker, then the column mapping table with guessed fields (editable), a "Keep only first middle name" switch and a country code.
  3. "N rows will become contacts".
  4. A target source/account picker, then `create` through `BulkRunner`, so a backup isn't needed but there's progress and a report.
- **Export:** scope, format, then `ACTION_CREATE_DOCUMENT` with the right MIME type and name (`contacts.vcf`, `contacts.xlsx`, …), or Share.
- **Backups:** list with time, action, source and count, plus Restore (`BulkRunner.undo`) and Share.
- **Settings:**
  - default country code;
  - Google accounts (add/remove);
  - a privacy line: "Phone contacts never leave your phone. The app only goes online when you use a signed-in Google account.";
  - About: version 1.0 and the GitHub link.

- [ ] **Step 1:** Write the failing tests:
  - `vcfImportCount`
  - `xlsxGuessesMapping`
  - `exportScopeSelectionOnly`
  - `openWithIntentRoutesToImport`
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*Io*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests (PASS), then `assembleDebug`.
- [ ] **Step 5: Manual device check** (Mubarak's Fold 8):
  1. Install the debug APK.
  2. With the phone source: bulk-set Department on 3 test contacts, then undo.
  3. Sign in to Google once the OAuth client exists (Task 13 guide): edit one contact and see it on contacts.google.com.
  4. Unfold the phone and check the two-pane layout.
- [ ] **Step 6:** Commit: `app: import, export, backups and settings`.

### Task 13: Release, docs and repo page

**Files:**
- Create: `docs/android/GOOGLE_SETUP.md`, the Android screenshots in `shots/android-*.png`
- Modify: `README.md`, `PRIVACY.md`, `CHANGELOG.md`, `.github/workflows/android.yml` (release step)

**Interfaces:**
- Consumes: the CI from Task 0.

- [ ] **Step 1:** Write `GOOGLE_SETUP.md`, an about-10-minute guide:
  1. Create a Google Cloud project and enable the People API.
  2. Set up the OAuth consent screen (External, Testing, add test users).
  3. Create an Android OAuth client with package `com.ilukezippo.vcfdesk` and the release keystore's SHA-1 (shown with `keytool -list -v`). Add the debug SHA-1 as a second client for testing.
  4. Note that Google sign-in uses accounts on the phone; signing in to a new account adds it to the phone, and its contact sync can be turned off in Android settings.
  5. Note the 100-tester limit, and that verification is needed for public use.
- [ ] **Step 2:** Generate a release keystore with `keytool -genkeypair -alias vcfdesk -keyalg RSA -keysize 4096 -validity 36500`, outside the repo. Hand it and its passwords to Mubarak to store, along with the base64 for the four GitHub secrets. It is never committed.
- [ ] **Step 3:** Take screenshots from a debug build on an emulator (Pixel Fold profile) with demo contacts "Name 1…Name 20" and numbers 55555555/99999999/66666666. Take the list (unfolded, two-pane), the editor, the bulk sheet, the preview and the import mapping.
- [ ] **Step 4:** Update the README in the DragWheel layout:
  - Add a second big button, "Download for Android", in the logo colour `#7b8cff`, linking to `releases/download/v1.0/VCF-Desk-Android-1.0.apk`.
  - Add an "Android" section with how to install the APK (allow installs from the browser), the screenshots and the Google setup link.
  - Update PRIVACY.md with the Google sign-in paragraph.
  - Add an "Android 1.0" entry to CHANGELOG.md.
- [ ] **Step 5:** Once the signing secrets are set, run the workflow on the `v1.0` tag (`workflow_dispatch` with ref `v1.0`). Expected: the release gains `VCF-Desk-Android-1.0.apk`, the Windows assets are unchanged, and the README links return 200.
- [ ] **Step 6:** Commit: `android: release workflow, docs and repo page`.

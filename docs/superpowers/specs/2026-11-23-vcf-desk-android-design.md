# VCF Desk for Android: design

Date: 2026-11-23 · Owner: Mubarak (ilukezippo) · Status: draft for approval

## Intent

VCF Desk is Mubarak's free Windows contacts manager (Python/Qt, github.com/ilukezippo/VCF-Desk, v1.0, subtitled "Contacts Manager"). He wants the same tool on his Android phone (Samsung Galaxy Z Fold 8). It should be a full contacts manager where **anything you can edit on one contact you can also change on many at once**. It must work on:

- the contacts stored on the phone (phone-only, Google, Samsung and any other synced account), and
- Google Contacts directly, by signing in to a Google account from inside the app.

He uses VCF Desk on employee contact lists with photos and Arabic names, so bulk changes to company, department, job title and similar fields, plus correct Arabic, are the everyday case.

## Goals

1. Browse, search, filter and sort contacts from any source, with photos.
2. Edit every common field of a single contact.
3. Select many contacts (all, the search results, or picked by hand) and change them in one step.
4. Import from and export to the same file formats as the desktop app, where they make sense on a phone.
5. Make bulk edits safe: preview first, back up automatically, and be able to undo.
6. Use the Fold well: list and editor side by side when unfolded, one column when folded.

## Non-goals

- Microsoft Access import/export. There's no Access driver on Android; the desktop app keeps that.
- Calling, messaging, dialer or caller-ID features.
- Syncing anything itself. Device contacts are synced by Android; Google mode talks to Google directly.
- A server, analytics, ads or telemetry.

## Anti-goals (a failure even if it "works")

- A bulk edit that silently damages contacts: lost photos, dropped phone numbers, mangled Arabic, or a change applied to the wrong contacts.
- Duplicating contacts by accident, e.g. the same edit written both to the phone and to Google for a Google contact that's synced to the phone.
- Needing internet or a sign-in for phone contacts.

## Constraints

- Kotlin, native Android. Mubarak has Android Studio and is fine with Kotlin.
- UI in English, with Arabic text (names, notes, addresses) displayed and edited correctly right to left. Version 1.0, subtitle "Contacts Manager", same name and branding as the desktop app.
- Public screenshots and sample data must use "Name" and fake numbers such as 55555555, 99999999 and 66666666, never real-looking people.
- Distributed as an APK on GitHub (no Play Store for now), from the **same repo as the desktop app**, github.com/ilukezippo/VCF-Desk (Mubarak's choice).
  - The Android project lives in an `android/` folder. The Windows app stays where it is in the repo root.
  - The two build workflows are separated by path: Android changes don't rebuild Windows, and Windows changes don't rebuild Android.
  - Each release carries both apps: the Windows installer and portable ZIP, plus `VCF-Desk-Android-<version>.apk`. The APK is added to the existing v1.0 release, so the Windows download links keep working.
  - The README gets a second big "Download for Android" button next to the Windows one, in the logo's color, plus an Android section. The page keeps the DragWheel layout.
- Mubarak keeps the source himself. The handover must say exactly where the source code is.

## Approaches considered

| | How it reaches contacts | Trade-off |
|---|---|---|
| A | Android's own contacts store (ContactsContract) only | Covers phone, Google and Samsung contacts on the phone with one code path, no sign-in, works offline. Can't reach a Google account that isn't on the phone. |
| B | Google People API only, with Google sign-in | Reaches Google directly, but needs a Google Cloud project, shows an "unverified app" warning, and can't touch phone-only contacts. |
| **C (chosen)** | **Both:** the contacts store by default, plus an optional Google sign-in | Covers everything, including Google accounts not added to the phone. About twice the work on the data side, and the Google part has setup cost (below). |

**Mubarak picked C.**

## Design

### Sources

The app works on one **source** at a time, picked from a switcher at the top:

1. **On this phone** (default). Reads and writes through Android's contacts store. Every contact shows a small account badge (Phone, Google, Samsung, …), with a filter by account. Edits to Google-account contacts here reach Google through Android's normal sync.
2. **Google account (signed in)**. Reads and writes through the Google People API for an account the user signs in to from the app. Several accounts can be added; each is its own source.

When a signed-in Google account is also synced to the phone, the app says so on that source ("This account is also on your phone; changes show up in both"). It never writes the same edit through both paths.

### Contact model

There's one in-app contact model, mapped to and from each source and to and from vCard. Fields:

- **Name:** prefix, first, middle, last, suffix, phonetic first and last, nickname, and the display name.
- **Typed lists:** phones, emails, postal addresses (street, city, region, postcode, country), websites, events (birthday and others), relations, and IM addresses.
- **Single values:** organization (company, department, job title), notes, photo, and groups/labels.

Anything a source has that the model doesn't know is kept and written back untouched, so editing never drops data.

### Screens

- **Contact list:** photo, display name (correct Arabic order), main number, and account badge. Search across all fields, filter by account and group, sort A → Z or Z → A. Long-press, or a Select button, starts multi-select, with "select all" and "select search results".
- **Contact editor:** every field above. Add, remove and relabel typed entries, set or change the photo, and see the full-size photo.
- **Bulk edit sheet**, for the selected contacts:
  - Set, replace or clear any single-value field: company, department, job title, notes (replace or append), city, country, prefix, suffix.
  - Find and replace text within a chosen field.
  - Add to or remove from a group/label.
  - Keep only the first middle name.
  - Add a country code to local numbers (+965 for 8-digit numbers by default; code and length can be changed).
  - Delete the selected contacts.
  - Copy or move the selected contacts to another source or account.
- **Tidy up:** find exact duplicates (same name and numbers) and remove them, keeping the most complete one. Merging near-duplicates is out of scope for 1.0.
- **Import:** pick a file (VCF, Excel .xlsx, CSV) and a target source. Columns are matched automatically (English and Arabic headings) and can be adjusted. Full names are split keeping names like عبد الله together, with an option to keep only the first middle name. There's a preview of how many contacts will be created.
- **Export:** all contacts, the search results or the selection. Formats: vCard for Android (3.0), iPhone/iCloud (3.0), vCard 4.0, Google Contacts CSV, Outlook CSV, Excel (opens right to left when the names are mostly Arabic), plain CSV, and one .vcf file per contact (zipped). Saved with Android's file picker or shared.
- **Backups:** a list of the automatic backups, each with restore and share.
- **Settings and about:** default country code, Google accounts (add and remove), version, GitHub link.

Fold layout: when unfolded, the list sits on the left and the editor or bulk sheet on the right. When folded, these are separate screens.

### Bulk edit safety

1. **Preview.** Before saving, show what will change: "Company → X on 214 contacts", with the contacts that will change listed and a before/after for any one of them.
2. **Backup.** Before writing, save a full VCF of the affected contacts, photos included, to the app's backups folder, labelled with the time and the action.
3. **Apply in batches** with a progress bar and cancel. Device contacts are written in batched operations; Google is written with batch requests of up to 200 contacts. If something fails part-way, report which contacts weren't changed.
4. **Undo.** "Undo last change" restores the affected contacts from that backup. Older backups can be restored from the Backups screen.

### Google sign-in setup (needed for source 2)

The People API needs an OAuth client in a Google Cloud project, linked to the app's package name and signing-key fingerprint. While the project stays in **Testing** mode, it works without Google's review for up to 100 test users that Mubarak adds by email. Anyone else downloading the APK can't use Google sign-in until the app passes Google's verification for the contacts scope; they can still use the phone source. The repo needs a short guide for creating the OAuth client. No client ID is needed in the code: Google matches the app by package name and signing key.

Google sign-in on Android always uses accounts added to the phone. Signing in to an account that isn't on the phone adds it to the phone's accounts; its contact sync can be turned off in Android settings so it only appears as the signed-in Google source.

### Formats and logic shared with the desktop app

Port, as plain Kotlin with unit tests, the desktop app's proven logic:

- vCard 2.1/3.0/4.0 reading and writing: quoted-printable, folded lines, `itemN.` labels, embedded photos.
- Name composing and splitting.
- Phone normalisation.
- Date normalisation.
- Column guessing for Excel/CSV import.
- .xlsx reading and writing without heavy libraries.
- Google and Outlook CSV layouts.
- The bulk-apply rules.

Reuse the desktop test cases where they fit.

### Privacy

The phone source works fully offline. The app only goes online when the user signs in to Google, and then only to Google. There's no telemetry. Ship a PRIVACY.md and an in-app line saying this.

## Decided by Mubarak

- Native Kotlin.
- Same GitHub repo as the desktop app.
- Both sources (approach C).
- Scope: a full contacts manager, with bulk edit for everything.

## Calls made in this design (check these)

- Same name as the desktop app: "VCF Desk", subtitle "Contacts Manager", version 1.0.
- English UI, with full right-to-left support for Arabic content.
- One source at a time, not a merged view of phone and Google.
- Near-duplicate merging is left out of 1.0. Exact-duplicate removal stays.
- Opening a .vcf file is an import into a source, not a separate "edit the file" mode.
- Backups are kept inside the app, and can be shared or exported.

## Left to the builder

- Architecture and libraries (e.g. Jetpack Compose with Material 3, coroutines, a local cache if useful), as long as large lists (thousands of contacts with photos) stay smooth.
- Minimum Android version, which must at least cover the Fold 8 and recent Samsung phones.
- Exact screen layouts, within the screens above.
- The build and release workflow (signed APK on GitHub Releases). Signing-key handling has to keep the key out of the repo.

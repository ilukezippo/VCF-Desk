# Changelog

## Android 1.0 — November 2026

The first Android release, for phones and foldables (Android 8 and newer).

- Edit your **phone's contacts or your Google contacts**; fields the app doesn't show are kept untouched
- **Bulk edit** with a preview, an automatic backup and undo
- **Import** (vCard, Excel, CSV) with column matching, and **export** (vCard, Google CSV, Outlook CSV, Excel)
- Right-to-left Arabic, photos, and a two-pane list and editor on unfolded foldables and tablets
- Google sign-in setup guide: docs/android/GOOGLE_SETUP.md

## 1.0 — November 2026

The first release.

### Contacts
- Opens **vCard 2.1, 3.0 and 4.0** files from Android, iPhone, Google, Outlook and Samsung, including the quoted-printable Arabic names old Android phones write
- **Never loses data:** fields VCF Desk doesn't show (Android extras, iPhone labels, groups, UIDs…) are kept exactly as they were
- Edit names, work details, phones, emails, addresses, websites, birthday, notes and photos; a **Raw vCard** tab for anything else
- Search by name, phone (with or without spaces) or email; add, duplicate, delete, sort A → Z and remove exact duplicates
- **Photos:** shown in the list and the editor, open full size, save the picture out, add or change one. Decoded in the background, so big files stay smooth
- **Bulk edit:** select many contacts (Ctrl+A for all) and change company, department, job title, groups, city, country or notes on all of them at once, keep only the first middle name, or add a country code (+965) to 8-digit numbers

### Import
- **Excel (.xlsx), CSV and Microsoft Access (.accdb / .mdb)**: each column is matched to a field automatically from English or Arabic headings (الاسم، الرتبة، القسم، رقم الهاتف…), and you can change any match
- Full names are split into first, middle and last name, keeping names like عبد الله together; optionally keep only the first middle name
- A Photo column can point to picture files in a folder, or hold the pictures themselves (Access OLE fields)
- Local 8-digit numbers get +965, Excel dates are converted

### Export
- **Android phone** (vCard 2.1), **iPhone / iCloud** (vCard 3.0), **vCard 4.0**
- **Google Contacts CSV** and **Outlook / Microsoft 365 CSV**, in the column layouts those services expect
- **Excel workbook** (right-to-left when the names are Arabic, photos saved to a folder next to it), plain **CSV**, **Access database** with photos
- **One .vcf file per contact**
- Export all contacts, the search results or only the selected ones

### Everywhere
- Modern dark design, Segoe UI Bold, correct right-to-left Arabic
- Undo and redo in every text box, shortcuts that work with the Arabic keyboard layout too
- Drop .vcf, Excel, CSV or Access files anywhere on the window
- Runs on any Windows PC; nothing else to install

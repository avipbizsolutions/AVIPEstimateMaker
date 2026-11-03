# Bihar Solar Circle

Free Android app (by AVIP Business Solutions LLP) for **Surya Saathi** partners (PM Surya Ghar installers and solar businesses)
to see AVIP Business Solutions LLP's B2B prices and create AVIP quotation PDFs in seconds.

**Download the latest APK:**
https://github.com/avipbizsolutions/AVIPEstimateMaker/releases/latest/download/Bihar-Solar-Circle.apk

## What a Surya Saathi can do
The home screen shows the tools as cards. The interface switches between English and
Hindi (Hindi script, technical words in English) from the chip in the top bar.

- **AVIP Cost Estimate**: browse AVIP B2B prices and make a cost-estimate PDF addressed to
  AVIP; one tap sends it to AVIP on WhatsApp. **My Cost Estimates** keeps them.
- **Customer Quotation** and **GST Tax Invoice**: the Surya Saathi's own documents for their
  customers. Items come from the AVIP catalog (HSN and GST % filled in) with their own selling
  rate, plus any custom line such as installation. CGST + SGST or IGST is picked from the
  place of supply; numbering is per financial year (e.g. INV/26-27/001, max 16 characters);
  a Composition dealer gets a Bill of Supply. A quotation converts to an invoice in one tap.
- **Google Drive**: the Surya Saathi links their own Drive once; every quotation and invoice
  PDF is uploaded to a "Bihar Solar Circle" folder (drive.file access only).
- **Find Installers** and **Transporters**: lists read from AVIP's two sheets, refreshed on
  launch and every time the list is opened, with Call and WhatsApp buttons.
- **My Business**: address, PIN, bank, UPI, prefixes and terms printed on their documents.
- Register once with **GSTIN (mandatory)**, checked on the phone as it is typed.
- The GitHub build checks GitHub on every launch and **updates itself automatically**.

## How prices get into the app
Everything comes from the Google Sheet (must stay *Anyone with the link → Viewer*):

| Tab | Purpose |
|---|---|
| **Worksheet** | The price list your team edits. Everything in the app comes from here. |
| **App Prices** | Auto-filled from the Worksheet by a formula — do not type in it. The note on cell A1 lists the Worksheet rules (category headings with GST, Wp in the Brand column for panels, "Pellet Pricing" and "Per pc" rows). A "Per pc" / "Per mtr" row under a packet item shows in the app as one card with a Packet \| Loose switch; a "Pellet Pricing" row under a panel shows as Single panel \| Pallet. |
| **Product Photos** | Keywords → photo link. Paste Google Drive share links or any image link; blank = category icon. |
| **App Settings** | Company details, bank, terms, validity, WhatsApp number, Grievance Officers, home-screen announcement. |
| **Surya Saathi** | Filled automatically when partners register (needs the Apps Script below). |
| **Quotations Log** | One row per AVIP cost estimate created (needs the Apps Script below). |

Installers and transporters come from two separate sheets (both *Anyone with the link → Viewer*):
**List of Installers** and **Pickup Tempo Phone Numbers**. Columns are found by their headings
(Name, Phone/Mobile, Base Location, Area Interested, Vehicle Type, Vehicle Number), so columns can
be added or reordered. To use other sheets, put their links in App Settings → `installers_sheet` /
`transporters_sheet`.

## Sign-up & quotation logging (one-time setup)
1. Open the sheet → Extensions → Apps Script → paste `apps-script/Code.gs` → Save.
2. Deploy → New deployment → Web app → Execute as **Me**, access **Anyone** → Deploy.
   (When updating an existing deployment: Deploy → Manage deployments → Edit → Version: New version.)
3. Paste the `/exec` URL into **App Settings → saathi_log_url**.

## Google Drive setup (one time, by AVIP)
Surya Saathis link their own Drive; Google only allows this once the app is registered:
1. https://console.cloud.google.com → create a project "Bihar Solar Circle".
2. APIs & Services → Library → enable **Google Drive API**.
3. OAuth consent screen → External → app name "Bihar Solar Circle", support email, add scope
   `.../auth/drive.file` → then **Publish app** (drive.file needs no Google review).
4. Credentials → Create credentials → OAuth client ID → **Android**:
   package `com.avip.estimatemaker`, SHA-1 `80:89:B9:E3:74:FF:E1:1C:E2:B4:6F:7F:92:F8:F6:AC:63:A3:FF:CB`.
   (For a future Play Store build add a second Android client with `in.avipsolar.biharsolarcircle`
   and the Play app-signing SHA-1.)
No app update is needed after this — the Link button starts working.

## Build
Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest
release, which installed apps pick up on their next launch. Keep `app/release.keystore` in the
repo — every update must be signed with it.

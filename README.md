# AVIP Surya Saathi

Free Android app for **Surya Saathi** partners (PM Surya Ghar installers and solar businesses)
to see AVIP Business Solutions LLP's B2B prices and create AVIP quotation PDFs in seconds.

**Download the latest APK:**
https://github.com/avipbizsolutions/AVIPEstimateMaker/releases/latest/download/AVIP-Surya-Saathi.apk

## What a Surya Saathi can do
- Register once: company, contact person, mobile and **GSTIN (mandatory)**. The GSTIN is checked
  on the phone as it is typed: format, state code and the official check digit.
- Browse products by category with brand filters, or search across everything.
- Build an **AVIP Quotation** PDF: AVIP letterhead, the Surya Saathi as buyer, GST split,
  bank details, godown, terms and validity. One tap sends it to AVIP on WhatsApp.
- Reopen, share or edit saved quotations.
- The app checks GitHub on every launch and **updates itself automatically**.

## How prices get into the app
Everything comes from the Google Sheet (must stay *Anyone with the link → Viewer*):

| Tab | Purpose |
|---|---|
| **Worksheet** | The price list your team edits. Everything in the app comes from here. |
| **App Prices** | Auto-filled from the Worksheet by a formula — do not type in it. The note on cell A1 lists the Worksheet rules (category headings with GST, Wp in the Brand column for panels, "Pellet Pricing" and "Per pc" rows). A "Per pc" / "Per mtr" row under a packet item shows in the app as one card with a Packet \| Loose switch. |
| **Product Photos** | Keywords → photo link. Paste Google Drive share links or any image link; blank = category icon. |
| **App Settings** | Company details, bank, terms, validity, WhatsApp number, Grievance Officers, home-screen announcement. |
| **Surya Saathi** | Filled automatically when partners register (needs the Apps Script below). |
| **Quotations Log** | One row per PDF created (needs the Apps Script below). |

## Sign-up & quotation logging (one-time setup)
1. Open the sheet → Extensions → Apps Script → paste `apps-script/Code.gs` → Save.
2. Deploy → New deployment → Web app → Execute as **Me**, access **Anyone** → Deploy.
   (When updating an existing deployment: Deploy → Manage deployments → Edit → Version: New version.)
3. Paste the `/exec` URL into **App Settings → saathi_log_url**.

## Build
Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest
release, which installed apps pick up on their next launch. Keep `app/release.keystore` in the
repo — every update must be signed with it.

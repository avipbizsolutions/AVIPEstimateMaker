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

## Logins
- **Surya Saathi (B2B customer):** registers in the app (company, contact, mobile, GSTIN, city,
  address) and logs in with mobile + 4-digit PIN. The default PIN is **1234** and must be changed
  at first login. Every registration and login is recorded in the **Surya Saathi** tab (device,
  Android and app version, last login, number of logins). In that tab AVIP can set **Status** to
  *Blocked* to stop someone, or tick **Reset PIN** (PIN goes back to 1234; they set a new one at
  the next login). PINs are stored scrambled. 5 wrong PINs lock the number for 15 minutes.
  Phones registered before logins existed get their login automatically once (PIN 1234).
- **AVIP Team:** Managers (Sales App Role *Manager*) and Founders (Expense *Admin*) in the
  **AVIP Team Directory** log in with their common AVIP mobile + PIN. They see: New Cost Estimate
  (pick a Surya Saathi or add a new B2B customer, set special rates, send the PDF to the customer),
  Cost Estimates I made, Surya Saathis list, Installers and Transporters. Every estimate goes to
  **Quotations Log** with **Made By** and **Special Rates**.

## Office script (one-time setup, and after every change of apps-script/)
1. Open the price sheet → Extensions → Apps Script. Paste `apps-script/Code.gs` over Code.gs, and add
   a second file **AuthClient.gs** with `apps-script/AuthClient.gs`. Save.
2. Project Settings (⚙) → Script properties → add **AUTH_URL** and **AUTH_KEY**: the same two values
   as in the AVIP Solar Sales script (Sales CRM Dashboard → Apps Script → Project Settings).
3. Select the function **setup** → Run → allow access. The log shows how many team members can log in.
4. Deploy → Manage deployments → ✏ Edit → Version: **New version** → Deploy (the URL stays the same).
   First time only: Deploy → New deployment → Web app, Execute as **Me**, access **Anyone**, and put
   the `/exec` URL in **App Settings → saathi_log_url**.

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

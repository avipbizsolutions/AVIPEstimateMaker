# AVIP Estimate Maker

Free Android app for PM Surya Ghar and solar vendors to see AVIP Business Solutions LLP's
B2B prices and create PDF estimates in seconds.

**Download the latest APK:**
https://github.com/avipbizsolutions/AVIPEstimateMaker/releases/latest/download/AVIP-Estimate-Maker.apk

## What vendors can do
- Register once (company, contact person, mobile, GST no).
- Browse products by category with brand filters, or search across everything.
- **Solar System Builder** – choose kW, DCR / Non-DCR panel and on-grid / hybrid inverter;
  panel count and kWp are worked out automatically.
- Make two kinds of PDF:
  - **AVIP Quotation** – AVIP letterhead, vendor as buyer, bank details, godown, terms, validity.
    One tap sends it to AVIP on WhatsApp to place the order.
  - **Customer Estimate** – vendor's own name, logo and GSTIN, margin % on AVIP rates,
    own items (installation, transport…), optional "hide item rates", optional
    PM Surya Ghar subsidy and net cost.
- Saved estimates can be reopened, shared or edited again.
- App updates itself from GitHub Releases.

## How prices get into the app
Everything comes from the Google Sheet (must stay *Anyone with the link → Viewer*):

| Tab | Purpose |
|---|---|
| **App Prices** | One product per row. Edit rates, add rows, set *In Stock* = No to hide a price. *Rate Basis* = "Per Wp" for panels (rate × Wp = price per panel). |
| **App Settings** | Company details, bank, terms, validity, WhatsApp number, subsidy slabs, home-screen announcement. |
| **Vendors** | Filled automatically when vendors register (needs the Apps Script below). |
| **Estimates Log** | One row per PDF created by any vendor (needs the Apps Script below). |

Changes appear in the app the next time it opens (or when the vendor taps refresh).
New categories: just type a new *Category* name and pick an *Icon* from the dropdown.

## Vendor & estimate logging (one-time setup)
1. Open the sheet → Extensions → Apps Script → paste `apps-script/Code.gs` → Save.
2. Deploy → New deployment → Web app → Execute as **Me**, access **Anyone** → Deploy.
3. Paste the `/exec` URL into **App Settings → vendor_log_url**.

## Build
Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest
release. Keep `app/release.keystore` in the repo — every update must be signed with it.

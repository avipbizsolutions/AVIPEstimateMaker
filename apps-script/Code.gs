/**
 * AVIP Estimate Maker – receives vendor sign-ups and estimate summaries from the app.
 *
 * Setup (one time):
 * 1. Open the price sheet > Extensions > Apps Script, paste this file, Save.
 * 2. Deploy > New deployment > type "Web app"
 *      Execute as: Me    Who has access: Anyone
 * 3. Copy the web app URL (ends in /exec) into the sheet's "App Settings" tab,
 *    key "vendor_log_url". The app picks it up on its next launch — no rebuild needed.
 */
const SHEET_ID = '1TEhXDY6fUY93h63IZzhWFwxA0pXwFkNflybA9hO9DVc';
const SHARED_KEY = 'avip-estimate-9Fk2'; // must match Remote.kt

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY) return reply({ ok: false, error: 'unauthorised' });
    lock.waitLock(10000);
    const book = SpreadsheetApp.openById(SHEET_ID);

    if (d.kind === 'vendor') {
      const sheet = book.getSheetByName('Vendors');
      const row = [
        new Date(), clean(d.company), clean(d.contact), clean(d.mobile),
        clean(d.gst), clean(d.city), clean(d.installId), clean(d.appVersion),
      ];
      // One row per phone: editing details updates the same row.
      const last = sheet.getLastRow();
      const ids = last > 1 ? sheet.getRange(2, 7, last - 1, 1).getValues().map(r => r[0]) : [];
      const i = ids.indexOf(row[6]);
      if (i >= 0) sheet.getRange(i + 2, 1, 1, row.length).setValues([row]);
      else sheet.appendRow(row);
    } else if (d.kind === 'estimate') {
      book.getSheetByName('Estimates Log').appendRow([
        new Date(), clean(d.number), clean(d.type), clean(d.company), clean(d.mobile),
        clean(d.customer), clean(d.items, 2000),
        Number(d.subTotal) || 0, Number(d.gst) || 0, Number(d.grandTotal) || 0,
      ]);
    } else {
      return reply({ ok: false, error: 'unknown kind' });
    }
    return reply({ ok: true });
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  } finally {
    try { lock.releaseLock(); } catch (_) {}
  }
}

// Trim, cap length, and stop text being read as a formula.
function clean(value, max) {
  const v = String(value == null ? '' : value).trim().slice(0, max || 150);
  return /^[=+\-@]/.test(v) ? "'" + v : v;
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

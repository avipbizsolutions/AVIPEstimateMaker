/**
 * Bihar Solar Circle app – receives Surya Saathi sign-ups, quotation summaries and
 * account-deletion requests from the app.
 *
 * Setup (one time):
 * 1. Open the price sheet > Extensions > Apps Script, paste this file, Save.
 * 2. Deploy > New deployment > type "Web app"
 *      Execute as: Me    Who has access: Anyone
 * 3. Copy the web app URL (ends in /exec) into the sheet's "App Settings" tab,
 *    key "saathi_log_url". The app picks it up on its next launch — no rebuild needed.
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

    if (d.kind === 'saathi' || d.kind === 'vendor') {
      const sheet = book.getSheetByName('Surya Saathi');
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
      book.getSheetByName('Quotations Log').appendRow([
        new Date(), clean(d.number), clean(d.company), clean(d.contact), clean(d.mobile),
        clean(d.gst), clean(d.items, 2000),
        Number(d.subTotal) || 0, Number(d.gstAmount) || 0, Number(d.grandTotal) || 0,
      ]);
    } else if (d.kind === 'delete') {
      // "Delete my account" in the app: remove the Surya Saathi's registration and
      // quotation rows, and keep only a dated note that a deletion was done.
      const mobile = clean(d.mobile), gst = clean(d.gst), id = clean(d.installId);
      let removed = 0;
      removed += deleteRows(book.getSheetByName('Surya Saathi'), r =>
        (id && String(r[6]) === id) || (mobile && gst && String(r[3]) === mobile && String(r[4]).toUpperCase() === gst.toUpperCase()));
      removed += deleteRows(book.getSheetByName('Quotations Log'), r =>
        mobile && gst && String(r[4]) === mobile && String(r[5]).toUpperCase() === gst.toUpperCase());
      let log = book.getSheetByName('Deletion Requests');
      if (!log) {
        log = book.insertSheet('Deletion Requests');
        log.appendRow(['Date', 'Source', 'Rows removed']);
      }
      log.appendRow([new Date(), 'App: Delete my account', removed]);
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

// Deletes data rows (below the header) that match; returns how many were removed.
function deleteRows(sheet, match) {
  if (!sheet || sheet.getLastRow() < 2) return 0;
  const rows = sheet.getRange(2, 1, sheet.getLastRow() - 1, sheet.getLastColumn()).getValues();
  let n = 0;
  for (let i = rows.length - 1; i >= 0; i--) {
    if (match(rows[i])) { sheet.deleteRow(i + 2); n++; }
  }
  return n;
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

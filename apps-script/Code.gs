/**
 * Bihar Solar Circle app – office web app on the price sheet.
 *
 *  • Surya Saathi (B2B customer) accounts: register, login with mobile + 4-digit PIN
 *    (default 1234, must be changed at first login), profile, PIN reset, delete.
 *    Every registration and login is recorded in the "Surya Saathi" tab.
 *  • AVIP Team login (Managers / Founders) through the common AVIP login
 *    (AVIP Team Directory → AVIP Auth). Needs AuthClient.gs as a second file.
 *  • Cost-estimate log ("Quotations Log"), with who made it.
 *
 * Setup (one time / after every change of this file):
 * 1. Price sheet > Extensions > Apps Script: paste this file as Code.gs and
 *    apps-script/AuthClient.gs as a second file "AuthClient.gs". Save.
 * 2. Project Settings → Script properties: AUTH_URL and AUTH_KEY — the same values
 *    as in the AVIP Solar Sales script.
 * 3. Run "setup" once from the editor and allow access.
 * 4. Deploy > Manage deployments > Edit (pencil) > Version: New version > Deploy.
 *    (First time: Deploy > New deployment > Web app, Execute as Me, access Anyone,
 *    and put the /exec URL in App Settings → saathi_log_url.)
 *
 * Product photos ("Product Photos" tab): one row per product in App Prices, kept in step
 * automatically (every 5 minutes, and from the "Bihar Solar Circle" menu). Paste a photo link
 * in "Photo URL", or managers / founders set it from the app (saved in AVIP's Drive).
 *
 * In the "Surya Saathi" tab AVIP can:
 *  • set Status to "Blocked" to stop a Saathi logging in (back to "Active" to allow);
 *  • tick "Reset PIN" — the PIN becomes 1234 and the Saathi sets a new one at next login.
 */
const SHEET_ID = '1TEhXDY6fUY93h63IZzhWFwxA0pXwFkNflybA9hO9DVc';
const SHARED_KEY = 'avip-estimate-9Fk2'; // must match Vendor.kt / Api.kt
const AUTH_APP = 'sales';                // team logins use the AVIP Solar Sales access
const DEFAULT_PIN = '1234';

// "Surya Saathi" tab: the first 8 columns are the original ones; the rest are added by setup().
const SAATHI_COLS = ['Registered On', 'Surya Saathi Company', 'Contact Person', 'Mobile', 'GST No', 'City',
  'Device ID', 'App Version', 'Address', 'Device', 'Android', 'Last Login', 'Logins', 'Status',
  'Reset PIN', 'Added By', 'PIN (scrambled)', 'Must Change PIN'];
const QUOTE_COLS = ['Created On', 'Quotation No', 'Surya Saathi Company', 'Contact Person', 'Mobile', 'GSTIN',
  'Items', 'Sub Total', 'GST', 'Grand Total', 'Made By', 'Special Rates'];

function doPost(e) {
  const lock = LockService.getScriptLock();
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.key !== SHARED_KEY) return reply({ ok: false, error: 'unauthorised' });

    // ---- AVIP Team (read-only or no sheet writes: no lock) ----
    if (d.kind === 'teamLogin') return reply(teamLogin(d));
    if (d.kind === 'teamSetPin') return reply(Object.assign({ ok: true }, authSetPin_(AUTH_APP, d)));
    if (d.kind === 'teamMe') return reply({ ok: true, user: teamUser(d, true) });
    if (d.kind === 'saathiList') { teamUser(d); return reply({ ok: true, saathis: saathiList() }); }

    lock.waitLock(20000);
    const book = SpreadsheetApp.openById(SHEET_ID);
    const sheet = saathiSheet(book);

    if (d.kind === 'register') return reply(register(sheet, d));
    if (d.kind === 'login') return reply(login(sheet, d));
    if (d.kind === 'me') return reply(me(sheet, d));
    if (d.kind === 'setPin') return reply(setPin(sheet, d));
    if (d.kind === 'profile') return reply(updateProfile(sheet, d));
    if (d.kind === 'addSaathi') return reply(addSaathi(sheet, d));
    if (d.kind === 'setPhoto') return reply(setPhoto(book, d));
    if (d.kind === 'editSaathi') return reply(editSaathi(sheet, d));
    if (d.kind === 'deleteSaathi') return reply(deleteSaathi(book, sheet, d));

    if (d.kind === 'saathi' || d.kind === 'vendor') {
      // Older app versions: details from a phone without login. Never touches the PIN.
      const rows = data(sheet);
      let i = rows.findIndex(r => clean(d.installId) && String(r[6]) === clean(d.installId));
      if (i < 0) i = rows.findIndex(r => last10(r[3]) === last10(d.mobile));
      const vals = [clean(d.company), clean(d.contact), last10(d.mobile), clean(d.gst).toUpperCase(), clean(d.city), clean(d.installId), clean(d.appVersion)];
      if (i >= 0) sheet.getRange(i + 2, 2, 1, 7).setValues([vals]);
      else {
        const row = blankRow(); row[0] = new Date();
        vals.forEach((v, k) => row[k + 1] = v);
        row[13] = 'Active'; row[14] = false;
        sheet.appendRow(row);
      }
    } else if (d.kind === 'estimate') {
      const log = book.getSheetByName('Quotations Log');
      ensureHeaders(log, QUOTE_COLS);
      let madeBy = 'Surya Saathi';
      if (d.teamToken) {
        try { const u = teamUser({ token: d.teamToken }); madeBy = u.name + ' (AVIP ' + (isFounder(u) ? 'Founder' : 'Manager') + ')'; }
        catch (_) { madeBy = 'AVIP Team (login expired)'; }
      }
      // The phone may resend after a slow reply: log each estimate number once.
      const last = log.getLastRow();
      if (last > 1 && log.getRange(2, 2, last - 1, 1).getValues().some(r => String(r[0]) === clean(d.number))) return reply({ ok: true, duplicate: true });
      log.appendRow([
        new Date(), clean(d.number), clean(d.company), clean(d.contact), last10(d.mobile),
        clean(d.gst), clean(d.items, 2000),
        Number(d.subTotal) || 0, Number(d.gstAmount) || 0, Number(d.grandTotal) || 0,
        madeBy, clean(d.special, 500),
      ]);
    } else if (d.kind === 'delete') {
      // "Delete my account": remove the Surya Saathi's registration and estimate rows,
      // and keep only a dated note that a deletion was done.
      const mobile = last10(d.mobile), gst = clean(d.gst), id = clean(d.installId);
      let removed = 0;
      removed += deleteRows(sheet, r =>
        (mobile && last10(r[3]) === mobile) || (id && String(r[6]) === id));
      removed += deleteRows(book.getSheetByName('Quotations Log'), r =>
        mobile && last10(r[4]) === mobile && (!r[10] || String(r[10]) === 'Surya Saathi'));
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
    return reply({ ok: false, error: String((err && err.message) || err) });
  } finally {
    try { lock.releaseLock(); } catch (_) {}
  }
}

/** Run once from the editor: adds the new columns and checks the team login link. */
function setup() {
  const book = SpreadsheetApp.openById(SHEET_ID);
  saathiSheet(book);
  fixSaathiRows_(book.getSheetByName('Surya Saathi'));
  ensureHeaders(book.getSheetByName('Quotations Log'), QUOTE_COLS);
  secret_();
  // Product photos: build the product-wise list now, keep it in step every 5 minutes.
  photoFolder_();
  Logger.log(syncPhotos());
  if (!ScriptApp.getProjectTriggers().some(t => t.getHandlerFunction() === 'syncPhotos')) {
    ScriptApp.newTrigger('syncPhotos').timeBased().everyMinutes(5).create();
  }
  try {
    Logger.log('Team login OK: ' + authUsers_(AUTH_APP).length + ' people in the AVIP Team Directory with Sales access.');
  } catch (e) {
    Logger.log('Team login not ready: ' + e.message);
  }
}

// =====================================================================
// Surya Saathi accounts
// =====================================================================

function register(sheet, d) {
  const mobile = last10(d.mobile);
  if (!/^[6-9]\d{9}$/.test(mobile)) return { ok: false, error: 'BADMOBILE: Enter a valid 10-digit mobile number.' };
  if (clean(d.company).length < 2 || clean(d.contact).length < 2) return { ok: false, error: 'Company and contact person are required.' };
  const rows = data(sheet);
  let i = rows.findIndex(r => last10(r[3]) === mobile);
  // An older phone registration (no login yet) is matched by its device id too.
  if (i < 0 && clean(d.installId)) i = rows.findIndex(r => String(r[6]) === clean(d.installId) && !r[16]);
  if (i >= 0 && rows[i][16]) return { ok: false, error: 'ALREADY: This mobile number is already registered. Please login with your PIN.' };

  const row = i >= 0 ? rows[i].slice() : blankRow();
  if (i < 0) row[0] = new Date();
  row[1] = clean(d.company); row[2] = clean(d.contact); row[3] = mobile; row[4] = clean(d.gst).toUpperCase();
  row[5] = clean(d.city); row[6] = clean(d.installId); row[7] = clean(d.appVersion); row[8] = clean(d.address, 300);
  row[9] = clean(d.device); row[10] = clean(d.android); row[11] = new Date(); row[12] = (Number(row[12]) || 0) + 1;
  row[13] = row[13] || 'Active'; row[14] = false; row[15] = row[15] || 'Self (app)';
  row[16] = pinHash_(mobile, DEFAULT_PIN); row[17] = true;
  if (String(row[13]).toLowerCase() === 'blocked') return { ok: false, error: 'BLOCKED: Your account is on hold. Please contact AVIP.' };
  writeRow(sheet, i, row);
  return { ok: true, token: token_(mobile, row[16]), profile: profileOf(row), mustSetPin: true };
}

function login(sheet, d) {
  const mobile = last10(d.mobile);
  const rows = data(sheet);
  const i = rows.findIndex(r => last10(r[3]) === mobile);
  if (i < 0) return { ok: false, error: 'NOTFOUND: This mobile number is not registered. Please register first.' };
  const row = rows[i].slice();
  if (String(row[13]).toLowerCase() === 'blocked') return { ok: false, error: 'BLOCKED: Your account is on hold. Please contact AVIP.' };
  // AVIP ticked "Reset PIN", or the row has no PIN yet (registered before logins): PIN is 1234.
  if (row[14] === true || String(row[14]).toUpperCase() === 'TRUE' || !row[16]) {
    row[16] = pinHash_(mobile, DEFAULT_PIN); row[17] = true; row[14] = false;
  }
  // Too many wrong PINs: wait 15 minutes (stops guessing).
  const cache = CacheService.getScriptCache();
  const tries = Number(cache.get('bad_' + mobile) || 0);
  if (tries >= 5) return { ok: false, error: 'LOCKED: Too many wrong PINs. Try again after 15 minutes, or ask AVIP to reset your PIN.' };
  if (pinHash_(mobile, String(d.pin || '')) !== String(row[16])) {
    cache.put('bad_' + mobile, String(tries + 1), 900);
    writeRow(sheet, i, row);
    return { ok: false, error: 'BADPIN: Wrong PIN. Forgot it? Ask AVIP to reset it.' };
  }
  cache.remove('bad_' + mobile);
  row[6] = clean(d.installId) || row[6]; row[7] = clean(d.appVersion) || row[7];
  row[9] = clean(d.device) || row[9]; row[10] = clean(d.android) || row[10];
  row[11] = new Date(); row[12] = (Number(row[12]) || 0) + 1;
  row[13] = row[13] || 'Active';
  writeRow(sheet, i, row);
  return { ok: true, token: token_(mobile, row[16]), profile: profileOf(row), mustSetPin: mustChange(row) };
}

function me(sheet, d) {
  const s = saathiFromToken(sheet, d.token);
  const row = s.row;
  row[7] = clean(d.appVersion) || row[7];
  row[11] = new Date();
  writeRow(sheet, s.i, row);
  return { ok: true, profile: profileOf(row), mustSetPin: mustChange(row) };
}

function setPin(sheet, d) {
  const s = saathiFromToken(sheet, d.token);
  const pin = String(d.newPin || '');
  if (!/^\d{4}$/.test(pin)) return { ok: false, error: 'PIN must be 4 digits.' };
  if (pin === DEFAULT_PIN) return { ok: false, error: 'Please choose a PIN other than 1234.' };
  const row = s.row;
  row[16] = pinHash_(row[3] = last10(row[3]), pin); row[17] = false;
  writeRow(sheet, s.i, row);
  return { ok: true, token: token_(last10(row[3]), row[16]), profile: profileOf(row) };
}

function updateProfile(sheet, d) {
  const s = saathiFromToken(sheet, d.token);
  const row = s.row;
  if (clean(d.company).length < 2 || clean(d.contact).length < 2) return { ok: false, error: 'Company and contact person are required.' };
  row[1] = clean(d.company); row[2] = clean(d.contact); row[4] = clean(d.gst).toUpperCase();
  row[5] = clean(d.city); row[8] = clean(d.address, 300);
  writeRow(sheet, s.i, row);
  return { ok: true, profile: profileOf(row) };
}

/** Finds the Saathi for a login token; throws "SESSION: …" if it is no longer valid. */
function saathiFromToken(sheet, tok) {
  const parts = String(tok || '').split('.');
  if (parts.length !== 3) throw new Error('SESSION: Please login.');
  const rows = data(sheet);
  const i = rows.findIndex(r => last10(r[3]) === parts[0]);
  if (i < 0) throw new Error('SESSION: Your account was removed. Please register again.');
  const row = rows[i].slice();
  if (String(row[13]).toLowerCase() === 'blocked') throw new Error('BLOCKED: Your account is on hold. Please contact AVIP.');
  if (!row[16] || token_(parts[0], row[16], parts[1]) !== tok) throw new Error('SESSION: Your PIN was changed or reset. Please login again.');
  return { i: i, row: row };
}

function mustChange(row) { return row[17] === true || String(row[17]).toUpperCase() === 'TRUE'; }

function profileOf(row) {
  return {
    company: String(row[1] || ''), contact: String(row[2] || ''), mobile: last10(row[3]),
    gst: String(row[4] || ''), city: String(row[5] || ''), address: String(row[8] || ''),
    since: row[0] instanceof Date ? row[0].getTime() : 0,
  };
}

// =====================================================================
// AVIP Team (Managers and Founders)
// =====================================================================

function isFounder(u) { return !!(u && u.roles && clean(u.roles.expense) === 'Admin'); }
function isManager(u) { return !!u && (u.role === 'Manager' || isFounder(u)); }

function teamLogin(d) {
  const r = authLogin_(AUTH_APP, d);           // { token, user, mustSetPin }
  if (!isManager(r.user)) return { ok: false, error: 'NOTEAM: Bihar Solar Circle is for AVIP Managers and Founders only.' };
  return Object.assign({ ok: true }, r, { founder: isFounder(r.user) });
}

/** The logged-in team member; must be a Manager or Founder. */
function teamUser(d, allowTempPin) {
  const u = authUser_(AUTH_APP, d, allowTempPin);
  if (!isManager(u)) throw new Error('NOTEAM: Bihar Solar Circle is for AVIP Managers and Founders only.');
  u.founder = isFounder(u);
  return u;
}

/** All Surya Saathis, newest first, for the team's customer list. */
function saathiList() {
  const sheet = saathiSheet(SpreadsheetApp.openById(SHEET_ID));
  return data(sheet).filter(r => last10(r[3]).length === 10).map(r => ({
    company: String(r[1] || ''), contact: String(r[2] || ''), mobile: last10(r[3]), gst: String(r[4] || ''),
    city: String(r[5] || ''), address: String(r[8] || ''),
    registered: r[0] instanceof Date ? r[0].getTime() : 0,
    lastLogin: r[11] instanceof Date ? r[11].getTime() : 0,
    status: String(r[13] || 'Active'), addedBy: String(r[15] || ''),
  })).sort((a, b) => b.registered - a.registered);
}

/** A manager adds a B2B customer who isn't in the app yet (they can later login with PIN 1234). */
function addSaathi(sheet, d) {
  const u = teamUser({ token: d.token });
  const mobile = last10(d.mobile);
  if (!/^[6-9]\d{9}$/.test(mobile)) return { ok: false, error: 'Enter a valid 10-digit mobile number.' };
  if (clean(d.company).length < 2) return { ok: false, error: 'Company name is required.' };
  const rows = data(sheet);
  if (rows.some(r => last10(r[3]) === mobile)) return { ok: false, error: 'ALREADY: This mobile number is already a Surya Saathi.' };
  const row = blankRow();
  row[0] = new Date(); row[1] = clean(d.company); row[2] = clean(d.contact); row[3] = mobile;
  row[4] = clean(d.gst).toUpperCase(); row[5] = clean(d.city); row[8] = clean(d.address, 300);
  row[13] = 'Active'; row[14] = false; row[15] = u.name + ' (AVIP)';
  row[16] = pinHash_(mobile, DEFAULT_PIN); row[17] = true;
  writeRow(sheet, -1, row);
  return { ok: true, saathi: { company: row[1], contact: row[2], mobile: mobile, gst: row[4], city: row[5], address: row[8],
    registered: Date.now(), lastLogin: 0, status: 'Active', addedBy: row[15] } };
}

/** A manager corrects a Surya Saathi's details. A new mobile number resets the PIN to 1234. */
function editSaathi(sheet, d) {
  const u = teamUser({ token: d.token });
  const rows = data(sheet);
  const i = rows.findIndex(r => last10(r[3]) === last10(d.mobile));
  if (i < 0) return { ok: false, error: 'This Surya Saathi is no longer in the sheet. Pull down to refresh the list.' };
  const mobile = last10(d.newMobile || d.mobile);
  if (!/^[6-9]\d{9}$/.test(mobile)) return { ok: false, error: 'Enter a valid 10-digit mobile number.' };
  if (clean(d.company).length < 2) return { ok: false, error: 'Company name is required.' };
  if (mobile !== last10(d.mobile) && rows.some((r, k) => k !== i && last10(r[3]) === mobile))
    return { ok: false, error: 'ALREADY: Another Surya Saathi already has this mobile number.' };
  const row = rows[i].slice();
  const mobileChanged = mobile !== last10(row[3]);
  row[1] = clean(d.company); row[2] = clean(d.contact); row[3] = mobile; row[4] = clean(d.gst).toUpperCase();
  row[5] = clean(d.city); row[8] = clean(d.address, 300);
  if (mobileChanged) { row[16] = pinHash_(mobile, DEFAULT_PIN); row[17] = true; }
  writeRow(sheet, i, row);
  return { ok: true, mobileChanged: mobileChanged, saathi: {
    company: row[1], contact: row[2], mobile: mobile, gst: row[4], city: row[5], address: row[8],
    registered: row[0] instanceof Date ? row[0].getTime() : 0, lastLogin: row[11] instanceof Date ? row[11].getTime() : 0,
    status: String(row[13] || 'Active'), addedBy: String(row[15] || '') } };
}

/** A manager deletes a Surya Saathi: their row goes; their logged estimates stay as records. */
function deleteSaathi(book, sheet, d) {
  const u = teamUser({ token: d.token });
  const mobile = last10(d.mobile);
  const removed = deleteRows(sheet, r => last10(r[3]) === mobile);
  if (!removed) return { ok: false, error: 'This Surya Saathi is no longer in the sheet.' };
  let log = book.getSheetByName('Deletion Requests');
  if (!log) { log = book.insertSheet('Deletion Requests'); log.appendRow(['Date', 'Source', 'Rows removed']); }
  log.appendRow([new Date(), 'AVIP Team: ' + u.name + ' deleted ' + clean(d.company) + ' (' + mobile.slice(0, 2) + '******' + mobile.slice(-2) + ')', removed]);
  return { ok: true };
}

// =====================================================================
// Sheet helpers
// =====================================================================

function saathiSheet(book) {
  let sheet = book.getSheetByName('Surya Saathi');
  if (!sheet) sheet = book.insertSheet('Surya Saathi');
  ensureHeaders(sheet, SAATHI_COLS);
  return sheet;
}

/** Writes any missing header cells; on first run formats Status and Reset PIN, hides the PIN columns. */
function ensureHeaders(sheet, cols) {
  if (!sheet) return;
  const have = sheet.getLastColumn() ? sheet.getRange(1, 1, 1, Math.max(sheet.getLastColumn(), cols.length)).getValues()[0] : [];
  let added = false;
  cols.forEach((c, k) => { if (!have[k]) { sheet.getRange(1, k + 1).setValue(c).setFontWeight('bold'); added = true; } });
  if (added && cols === SAATHI_COLS) {
    const n = Math.max(sheet.getMaxRows() - 1, 1);
    sheet.getRange(2, 14, n, 1).setDataValidation(SpreadsheetApp.newDataValidation()
      .requireValueInList(['Active', 'Blocked'], true).setAllowInvalid(false).build());
    sheet.getRange(2, 15, n, 1).insertCheckboxes();
    sheet.hideColumns(17, 2);
    const last = sheet.getLastRow();
    if (last > 1) {
      const st = sheet.getRange(2, 14, last - 1, 1);
      st.setValues(st.getValues().map(v => [v[0] || 'Active']));
    }
  }
}

function data(sheet) {
  const last = sheet.getLastRow();
  if (last < 2) return [];
  return sheet.getRange(2, 1, last - 1, SAATHI_COLS.length).getValues();
}

function blankRow() { return SAATHI_COLS.map(() => ''); }

/** i = index in data() (row i + 2), or -1 to append. */
function writeRow(sheet, i, row) {
  const r = row.slice(0, SAATHI_COLS.length);
  r[3] = "'" + last10(r[3]); // keep the mobile number as text
  if (i < 0) {
    // First row with no mobile number (the tab has pre-filled Status / tick boxes further down).
    const rows = data(sheet);
    const free = rows.findIndex(x => !last10(x[3]) && !x[1]);
    const at = free >= 0 ? free + 2 : Math.max(sheet.getLastRow(), 1) + 1;
    sheet.getRange(at, 1, 1, r.length).setValues([r]);
  } else sheet.getRange(i + 2, 1, 1, r.length).setValues([r]);
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

/** One-time tidy-up: moves registrations that landed far down (row 1000+) to the top. */
function fixSaathiRows_(sheet) {
  if (!sheet || sheet.getLastRow() < 2) return;
  const all = sheet.getRange(2, 1, sheet.getLastRow() - 1, SAATHI_COLS.length).getValues();
  const real = all.filter(r => last10(r[3]) || r[1]);
  const blanks = all.length - real.length;
  if (!blanks) return;
  const out = real.concat(Array.from({ length: blanks }, () => SAATHI_COLS.map((c, k) => k === 13 ? 'Active' : k === 14 ? false : '')));
  out.forEach(r => { if (last10(r[3])) r[3] = "'" + last10(r[3]); });
  sheet.getRange(2, 1, out.length, SAATHI_COLS.length).setValues(out);
}

// =====================================================================
// Product photos — one row per product, keyed by the App Prices ID
// =====================================================================

const PHOTO_COLS = ['Product ID', 'Photo URL', 'Preview', 'Category', 'Product', 'Brand', 'In Price List', 'Updated By', 'Updated On'];

/** Shows the "Bihar Solar Circle" menu in the sheet. */
function onOpen() {
  SpreadsheetApp.getUi().createMenu('Bihar Solar Circle')
    .addItem('Refresh product photo list', 'syncPhotosMenu')
    .addToUi();
}

function syncPhotosMenu() { SpreadsheetApp.getActive().toast(syncPhotos(), 'Product photos'); }

function photoKey_(product, brand) { return (String(product) + '|' + String(brand)).toLowerCase().replace(/\s+/g, ' ').trim(); }
function words_(t) { return String(t).toLowerCase().split(/[^a-z0-9.]+/).map(w => w.replace(/^\.+|\.+$/g, '')).filter(Boolean); }

/**
 * Rebuilds the Product Photos tab from App Prices, keeping every photo already set
 * (matched by product ID, or by product + brand if the category name changed).
 * Products no longer in the price list keep their row at the bottom if they have a photo.
 * Runs every 5 minutes; writes only when something changed.
 */
function syncPhotos() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(20000)) return 'Busy, try again';
  try {
    return syncPhotosCore_();
  } finally {
    lock.releaseLock();
  }
}

function syncPhotosCore_() {
  {
    const book = SpreadsheetApp.openById(SHEET_ID);
    const prices = book.getSheetByName('App Prices');
    const n = prices.getLastRow() - 1;
    if (n < 1) return 'App Prices is empty';
    // Same product in two sizes (e.g. 625 / 620 Wp) shares one ID, and one photo row.
    const seenId = {};
    const items = prices.getRange(2, 1, n, 5).getValues()
      .filter(r => String(r[0]).trim() && !seenId[String(r[0]).trim()] && (seenId[String(r[0]).trim()] = true))
      .map(r => ({ id: String(r[0]).trim(), cat: String(r[1]), product: String(r[3]), brand: String(r[4]) }));

    let sheet = book.getSheetByName('Product Photos');
    if (!sheet) sheet = book.insertSheet('Product Photos');
    const old = sheet.getLastRow() > 1 ? sheet.getRange(1, 1, sheet.getLastRow(), Math.max(sheet.getLastColumn(), 2)).getValues() : [[]];
    const byId = {}, byName = {};
    let rules = null;
    if (String(old[0][0]).trim() === 'Product ID') {
      old.slice(1).forEach(r => {
        const rec = { url: String(r[1] || '').trim(), by: r[7] || '', on: r[8] || '', cat: r[3], product: r[4], brand: r[5] };
        if (String(r[0]).trim()) byId[String(r[0]).trim()] = rec;
        if (rec.url) byName[photoKey_(r[4], r[5])] = rec;
      });
    } else {
      // First run: the old keyword rows. Keep a copy, and give each product the photo the
      // keywords picked (the rule with the most matching words), for the team to check.
      rules = old.slice(1).map(r => ({ w: words_(r[0]), url: String(r[1] || '').trim() })).filter(r => r.w.length && r.url);
      const copy = book.getSheetByName('Photo Keywords (old)') || sheet.copyTo(book).setName('Photo Keywords (old)');
      copy.hideSheet();
    }

    const rows = [], used = {};
    items.forEach(it => {
      let rec = byId[it.id] || byName[photoKey_(it.product, it.brand)];
      if (!rec && rules) {
        const have = words_(it.cat + ' ' + it.brand + ' ' + it.product);
        let best = null;
        rules.forEach(r => { if (r.w.every(w => have.indexOf(w) >= 0) && (!best || r.w.length > best.w.length)) best = r; });
        if (best) rec = { url: best.url, by: 'Keyword (please check)', on: '' };
      }
      if (rec) used[photoKey_(rec.product || it.product, rec.brand || it.brand)] = true;
      rows.push([it.id, rec ? rec.url : '', '', it.cat, it.product, it.brand, 'Yes', rec ? rec.by : '', rec ? rec.on : '']);
    });
    const listed = {}; items.forEach(it => listed[it.id] = true);
    Object.keys(byId).forEach(id => {
      const r = byId[id];
      if (!listed[id] && r.url && !used[photoKey_(r.product, r.brand)]) rows.push([id, r.url, '', r.cat, r.product, r.brand, 'No', r.by, r.on]);
    });

    // Nothing changed? Leave the tab alone.
    const now = old.length > 1 ? old.slice(1).map(r => [r[0], r[1], r[6]].join('¦')).join('\n') : '';
    const next = rows.map(r => [r[0], r[1], r[6]].join('¦')).join('\n');
    if (now === next && String(old[0][0]).trim() === 'Product ID') return 'Up to date: ' + items.length + ' products';

    sheet.clear();
    sheet.getRange(1, 1, 1, PHOTO_COLS.length).setValues([PHOTO_COLS]).setFontWeight('bold').setBackground('#0B5A33').setFontColor('#FFFFFF');
    sheet.setFrozenRows(1);
    if (rows.length) {
      sheet.getRange(2, 1, rows.length, PHOTO_COLS.length).setValues(rows);
      sheet.getRange(2, 3, rows.length, 1).setFormulas(rows.map((r, k) => {
        const b = 'B' + (k + 2);
        return ['=IF(' + b + '="","",IMAGE(IF(REGEXMATCH(' + b + ',"/d/|id="),"https://lh3.googleusercontent.com/d/"&REGEXEXTRACT(' + b + ',"(?:/d/|id=)([-\\w]{20,})"),' + b + ')))'];
      }));
      sheet.setRowHeights(2, rows.length, 64);
      const missing = rows.map(r => [r[1] ? null : '#FFF4E0']);
      sheet.getRange(2, 2, rows.length, 1).setBackgrounds(missing);
      sheet.getRange(2, 1, rows.length, 1).setFontColor('#5F6B64');
    }
    sheet.setColumnWidth(1, 220); sheet.setColumnWidth(2, 320); sheet.setColumnWidth(3, 80);
    sheet.setColumnWidth(4, 140); sheet.setColumnWidth(5, 240); sheet.setColumnWidth(6, 110);
    sheet.getRange(1, 2).setNote('Paste a photo link for each product (Google Drive "Anyone with the link" share link, or any image link). Blank = category icon. Orange = no photo yet. Managers can also set photos from the app. Rows follow the price list automatically; do not edit Product ID.');
    const without = rows.filter(r => r[6] === 'Yes' && !r[1]).length;
    return items.length + ' products · ' + without + ' without a photo';
  }
}

function photoFolder_() {
  const p = PropertiesService.getScriptProperties();
  const id = p.getProperty('PHOTO_FOLDER');
  if (id) { try { const f = DriveApp.getFolderById(id); if (!f.isTrashed()) return f; } catch (_) {} }
  const f = DriveApp.createFolder('Bihar Solar Circle – Product Photos');
  p.setProperty('PHOTO_FOLDER', f.getId());
  return f;
}

/** Manager / Founder sets (or removes, with no image) a product's photo from the app. */
function setPhoto(book, d) {
  const u = teamUser({ token: d.token });
  const id = String(d.id || '').trim();
  if (!id) return { ok: false, error: 'No product' };
  let url = '';
  if (d.image) {
    const blob = Utilities.newBlob(Utilities.base64Decode(d.image), 'image/jpeg',
      (clean(d.product) + ' ' + clean(d.brand)).replace(/[^\w .-]+/g, ' ').trim().slice(0, 80) + '.jpg');
    const file = photoFolder_().createFile(blob);
    file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
    url = 'https://drive.google.com/file/d/' + file.getId() + '/view';
  }
  const sheet = book.getSheetByName('Product Photos');
  if (!sheet || String(sheet.getRange(1, 1).getValue()).trim() !== 'Product ID') syncPhotosCore_();
  const ph = book.getSheetByName('Product Photos');
  const ids = ph.getLastRow() > 1 ? ph.getRange(2, 1, ph.getLastRow() - 1, 1).getValues().map(r => String(r[0]).trim()) : [];
  let row = ids.indexOf(id) + 2;
  if (row < 2) {
    row = ph.getLastRow() + 1;
    ph.getRange(row, 1, 1, 7).setValues([[id, '', '', clean(d.category), clean(d.product), clean(d.brand), 'Yes']]);
    const b = 'B' + row;
    ph.getRange(row, 3).setFormula('=IF(' + b + '="","",IMAGE(IF(REGEXMATCH(' + b + ',"/d/|id="),"https://lh3.googleusercontent.com/d/"&REGEXEXTRACT(' + b + ',"(?:/d/|id=)([-\\w]{20,})"),' + b + ')))');
  }
  ph.getRange(row, 2).setValue(url).setBackground(url ? null : '#FFF4E0');
  ph.getRange(row, 8, 1, 2).setValues([[u.name + ' (app)', new Date()]]);
  return { ok: true, url: url };
}

// =====================================================================
// PINs and login tokens (never stored or sent as plain numbers)
// =====================================================================

function secret_() {
  const p = PropertiesService.getScriptProperties();
  let s = p.getProperty('SAATHI_SECRET');
  if (!s) { s = Utilities.getUuid() + Utilities.getUuid(); p.setProperty('SAATHI_SECRET', s); }
  return s;
}

function hex_(bytes) { return bytes.map(b => ('0' + (b & 0xff).toString(16)).slice(-2)).join(''); }

function pinHash_(mobile, pin) {
  return hex_(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, secret_() + '|' + last10(mobile) + '|' + pin));
}

/** mobile.issued.signature — changes (and old ones stop working) whenever the PIN changes. */
function token_(mobile, pinHash, issued) {
  const t = issued || String(Date.now());
  const sig = Utilities.base64EncodeWebSafe(Utilities.computeHmacSha256Signature(mobile + '|' + t + '|' + pinHash, secret_())).replace(/=+$/, '');
  return mobile + '.' + t + '.' + sig;
}

// =====================================================================

function last10(v) { return String(v == null ? '' : v).replace(/\D/g, '').slice(-10); }

// Trim, cap length, and stop text being read as a formula.
function clean(value, max) {
  const v = String(value == null ? '' : value).trim().slice(0, max || 150);
  return /^[=+\-@]/.test(v) ? "'" + v : v;
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

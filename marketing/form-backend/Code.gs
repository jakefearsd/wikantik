/**
 * Wikantik landing-page lead capture.
 * Bind this script to the target Google Sheet (Extensions → Apps Script),
 * then Deploy → New deployment → Web app, "Execute as: me",
 * "Who has access: Anyone". Copy the /exec URL into marketing/index.html
 * (LEAD_ENDPOINT). See marketing/README.md.
 *
 * Sheet columns (row 1 headers, created on first run): timestamp, name, email, use_case
 */
function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return _json({ ok: false, error: "no body" });
    }
    var body = JSON.parse(e.postData.contents);
    var name = String(body.name || "").slice(0, 200);
    var email = String(body.email || "").slice(0, 200);
    var useCase = String(body.use_case || "").slice(0, 2000);

    if (!name || !email) {
      return _json({ ok: false, error: "name and email required" });
    }

    var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
    if (sheet.getLastRow() === 0) {
      sheet.appendRow(["timestamp", "name", "email", "use_case"]);
    }
    sheet.appendRow([new Date(), name, email, useCase]);
    return _json({ ok: true });
  } catch (err) {
    // Log with context; never fail silently.
    console.error("Lead doPost failed: " + err);
    return _json({ ok: false, error: "server error" });
  }
}

function _json(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

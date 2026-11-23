# WLTW Dialer

Assisted calling against the Lead Outreach CRM: the app opens the phone dialer with the next lead's number filled in, you tap Call yourself, then you record what happened and it saves the answer to the CRM.

Built by Muse-proto.

This tree is **v1.9** (`versionName` 1.9, `versionCode` 10). No APK is included; build from source.

## Build

The Gradle project lives in `android/`. On a machine with the Android SDK:

```bash
cd android
gradle assembleDebug
```

A sandbox-friendly command-line script is also included:

```bash
cd android
./build-apk-manual.sh
```

The script expects a local Android SDK (`$HOME/Android/Sdk`) and may need its JDK / build-tools paths adjusted. It does not place calls and talks only to the CRM base URL you enter in Settings.

## Set up

In the app, open **Settings**:

| Field | Value |
|---|---|
| CRM address | your CRM URL (default example: `https://crm.welivethisway.com`) |
| CRM API key | create one in the CRM under Settings → Bot API keys |
| Campaign filter | e.g. `delivery` |
| Status filter | e.g. `new` |

The API key stays on the phone in the app's private storage. No key is baked into the source.

See the rest of this README for queue, dispositions, and CRM API notes.

---

## Original notes

# WLTW Dialer — assisted calling against the Lead Outreach CRM

Call through your leads one by one. The app opens the phone dialer
with the number filled in — you tap Call yourself — then asks what
happened, saves the answer to the CRM, and shows the next lead.

## Install

1. Copy `WeLiveThisWay-Dialer.apk` to the Android phone (USB, email it
   to yourself, Google Drive, etc.).
2. On the phone, open the APK and allow **Install unknown apps** for
   the app you open it from, if asked. Tap Install.
3. The app is called **WLTW Dialer**. It asks for no permissions
   except Internet, and sends nothing anywhere except to the CRM
   address in Settings.

## Set up (one time)

In the app, tap **Settings**:

| Field | Value |
|---|---|
| CRM address | `https://crm.welivethisway.com` (already filled in) |
| CRM API key | paste your key — make one in the CRM under Settings → Bot API keys (shown once; copy it there) |
| Campaign filter | `delivery` (already filled in) |
| Status filter | `new` (already filled in) |

Tap **Save settings**. The key stays on the phone only, in the app's
private storage. No key is baked into the app or stored in this folder.

## Calling through a queue

1. Tap **Load queue** — pulls up to 100 leads from the CRM
   (`GET /api/v1/listLeads?status=new&campaign=delivery&limit=100`).
2. The lead shows business name, phone, contact name and notes.
   The top line reads **Called: X · Left: Y**. Under the lead, any
   previous calls for it are listed (newest 3: date, outcome, and
   the note you wrote — e.g. `2026-11-22 — Callback — "call back
   later"`), read back from the CRM, so you see what you wrote last
   time before calling again. If the CRM hasn't been updated with
   the new `listCalls` endpoint yet, that section just stays empty —
   nothing errors.
3. Tap **CALL** — the system dialer opens with the number filled in.
   The app never places a call by itself; you tap Call in the dialer.
4. When you come back to the app, tap what happened:
   Interested / Not interested / No answer / Voicemail / Callback /
   Wrong number / Do not contact / Not qualified / Shift campaign.
   You can type an optional note first ("Add a note (optional)") — it
   is sent with the call and stored in the CRM, and if the save fails
   the note stays in the field for Retry. **Shift campaign** opens a
   picker of target campaigns (from the CRM, plus "restaurant",
   minus the campaign the lead is already on) — tap one and the lead
   moves there (`{"outcome": "shift_campaign", "to_campaign":
   "<picked>"}`); a food-service name (restaurant, cafe, bakery,
   pizza, …) pre-selects "restaurant", but any campaign can be
   tapped. It is also available before calling, as a button under
   CALL next to **Not qualified**, for a lead that belongs in a
   different campaign — no call, no note, same save-then-advance rule.
5. The answer is posted to the CRM
   (`POST /api/v1/recordCall`, `{"lead_id": 12, "outcome": "interested"}`).
   **The next lead appears only after the CRM answers 200.** If the
   save fails, the lead stays on screen with the error and a **Retry
   saving** button — a disposition is never lost.
6. **Skip** moves to the next lead without saving anything.
   **Clear queue** empties the queue (dispositions already saved in
   the CRM stay saved).

If the phone is turned or the app is closed mid-session, the queue
and your place in it are remembered when you reopen it.

## Queue overview — pick which lead to call

Tap **View queue** to see the whole loaded queue in one list, in the
same order the dialer steps through it. Each row shows the business,
the phone, and the outcome already saved for leads you have
dispositioned. The list opens on the lead you are on, highlighted.
Type in the search box to filter by business name or phone — typing
"pharmacy" shows only the pharmacies. Tap a row to jump to that
lead (even one already dispositioned — calling it again and picking
a new outcome saves a new record, nothing is re-posted just by
jumping). **Load more** at the bottom fetches the next 100 leads
from the CRM and appends them. **Previous** next to Skip steps back
to the lead before the current one without saving anything.

## CSV import (fallback, no CRM needed)

Tap **Import CSV** and pick a `.csv` file with columns
`business,phone,contact,notes` (extra columns are ignored; a file
with no header row is read as business,phone,contact,notes in that
order). Rows whose phone matches a lead already in the loaded CRM
queue are skipped as duplicates. Rows with no CRM match are queued
as **phone-only**: after you call one, its outcome is saved **on the
phone only** and listed under "Saved on this phone only — not in the
CRM", with the business, phone, outcome and time. Those outcomes are
not synced anywhere — they live in the app until you clear them by
reinstalling or clearing the app's data.

## ⚠️ The CRM needs one new endpoint before call outcomes can save

The app posts outcomes to `POST /api/v1/recordCall`
(`{"lead_id": <id>, "outcome": "<one of interested|not_interested|no_answer|voicemail|callback|wrong_number|do_not_contact>"}`,
`Authorization: Bearer <key>`, answer 200 on success). The
self-hosted CRM (`crm.py`) does **not** have that endpoint yet — it
has `listLeads`, `sendSms`, `listInbox` and `recordInboundReply`, but
no `recordCall`. Until it is added, tapping a disposition will show
"CRM has no such address (404…)" and the lead will stay on screen
with Retry — by design, so nothing is lost. Loading the queue and
CSV import both work without it.

Suggested behaviour for the endpoint (to match the app): look the
lead up by `lead_id`, record the call + outcome, and move the lead to
a status that fits (`interested` → replied/taken over for the operator's
follow-up, `do_not_contact` → do_not_contact + opted out,
`wrong_number` → do_not_contact, the rest stay in the pipeline or
move to a called state). Any non-200 answer is shown to the operator as-is.

## Colours and style

Same look as the other WLTW apps: navy `#0d1f3c`, teal `#0fb5ae`,
cream `#fff7e8`, with the "WE LIVE THIS WAY / A SOUTH FLORIDA
COURIER CO" banner at the top. Big tap targets: CALL is 72dp tall,
disposition buttons 56dp.

## Rebuilding the app

- On a normal machine: open `android/` in Android Studio, or run
  `gradle assembleDebug` (JDK 17, Android SDK, compileSdk 34, minSdk 24).
- In this VM the Gradle daemon could not run (raw TCP, including
  loopback, is intercepted here, so the Gradle client can never reach
  its daemon). The APK here was built with the SDK tools directly:
  `android/build-apk-manual.sh` (aapt2 → javac → d8 → zipalign →
  apksigner). It needs JDK 17 + Android SDK under `~/Android/Sdk`.
- Signing: the APK is signed with the persistent keystore in
  `keystore/wltw-dialer.keystore` (alias `wltw-dialer`). Its password
  is in `keystore/keystore.pass` on the build machine only — keep both
  files; updates must use this same key or they will not install over
  the old version. The build script creates them once if missing and
  never regenerates them.
- The core logic (`Lead`, `CrmApi`, `CsvParser`) has no Android
  dependencies and is covered by a JVM test harness (24 checks:
  CSV parsing, phone matching, CRM request/response handling
  against a stub connection) — see the build notes in the project
  folder if you need to re-run it.

## How it works (exact API calls)

Base URL: the CRM address in Settings (default
`https://crm.welivethisway.com`). Auth header on every call:
`Authorization: Bearer <key>`. User-Agent: `WLTW-Dialer/1.0 (Android)`.

- **Load queue** — `GET /api/v1/listLeads?status=new&campaign=delivery&limit=100`
  (status/campaign from Settings). Answers `{"leads": [...]}` with
  `id`, `business`, `phone`, `name`, `notes`, `campaign`, `status`.
  Any non-200 is shown as a plain error message (401 = wrong key).
- **Previous calls** — `GET /api/v1/listCalls?lead_id=<id>`, newest
  first, each with `outcome`, `note`, `called_at`, `actor`. The app
  fetches this when a lead opens and shows the newest 3 under the
  lead. Needs the updated `crm.py` on the server (v1.4 change); until
  the server is updated the CRM answers 404 and the app just shows
  no previous calls — no error, no retry loop.
- **Save outcome** — `POST /api/v1/recordCall`, JSON body
  `{"lead_id": <id>, "outcome": "<value>"}`, `Content-Type:
  application/json`. Only a 200 advances to the next lead.
  "Shift campaign" adds `"to_campaign": "<picked campaign>"` to
  the same body (`"outcome": "shift_campaign"`).
- **List campaigns** — `GET /api/v1/campaigns`, answers
  `{"campaigns": ["delivery", ...]}`. The shift picker is built
  from this list (plus "restaurant" if absent, minus the lead's
  current campaign); if the call fails or the endpoint is not live
  yet, the picker falls back to `["delivery", "restaurant"]`.

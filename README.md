# WLTW Dialer

WLTW Dialer is an Android call-queue for sales reps. It works with the self-hosted [muse-crm](https://github.com/We-Live-This-Way/muse-crm) lead CRM. AI sales bots on OpenMausBot or Rakazo — or any agent that can make an HTTP call or use MCP — can find and import leads into that CRM with their own API key. You then work that list from the phone: tap through the queue, place each call yourself, log what happened, and fix a lead's business or contact name when something is wrong.

The CRM stays up to date as you go. The app never places a call on its own: it opens the system dialer with the number filled in, and you tap Call.

This tree is **v1.10.4** (`versionName` 1.10.4, `versionCode` 15).

## What you need

- An Android phone running Android 7.0 or newer
- A running [muse-crm](https://github.com/We-Live-This-Way/muse-crm) server, and an API key created in that CRM under Settings → Bot API keys
- Optional: AI sales bots on OpenMausBot or Rakazo (or any agent that can call the CRM over HTTP or use the CRM MCP tool) that import leads with their own API key

## Install

1. Download `WLTW-Dialer-1.10.4.apk` from the [latest release](https://github.com/We-Live-This-Way/muse-dialer/releases/latest).
2. On the phone, open the file. If Android asks, allow installs from your browser or file manager, then tap Install.
3. Open **WLTW Dialer**, go to **Settings**, and enter your CRM address and API key. You can also filter the queue by campaign and status.

If you already installed an older test build, uninstall it first. This release is signed with a new key, so it cannot update the old app in place. Leads and call history live on the CRM server and are not lost.

The API key stays on the phone in the app's private storage. No key is baked into the source. The app asks only for Internet permission, and it talks only to the CRM address you enter.

## What's new in 1.10.4

- Filter the on-phone queue to a single campaign
- Edit a lead's business or contact name from the phone

## Using the queue

1. Tap **Load queue** to pull up to 100 leads from the CRM (`GET /api/v1/listLeads`, using the status and campaign in Settings).
2. The lead card shows the business name, phone, contact name, and notes. The top line reads **Called: X · Left: Y**. Previous calls for that lead (newest 3: date, outcome, and your note) are read back from the CRM so you can see what you wrote last time. If the server has no call history yet, that section stays empty.
3. Tap **CALL** — the system dialer opens with the number filled in.
4. When you return, pick what happened: Interested, Not interested, No answer, Voicemail, Callback, Wrong number, Do not contact, Not qualified, or Shift campaign. You can type an optional note first. **Shift campaign** opens a picker of target campaigns from the CRM; the lead moves there after a successful save. It is also available before calling, next to **Not qualified**, for a lead that belongs in a different campaign.
5. The answer is posted to `POST /api/v1/recordCall`. **The next lead appears only after the CRM answers 200.** If the save fails, the lead stays on screen with the error and a **Retry saving** button — a disposition is never dropped.
6. **Skip** moves to the next lead without saving. **Clear queue** empties the local queue (dispositions already saved in the CRM stay saved).

If the phone is turned or the app is closed mid-session, the queue and your place in it are remembered when you reopen it.

Tap **View queue** to see the whole loaded list, in the same order the dialer steps through it. Type in the search box to filter by business name or phone. **A campaign picker at the top of the queue (v1.10.4) narrows the list to one campaign.** "All campaigns" shows everything. The pick is remembered. Tap a row to jump to that lead. **Load more** fetches the next 100 leads. **Previous**, next to Skip, steps back without saving.

**Edit name** on the lead card posts only the fields you actually changed to `POST /api/v1/updateLead`. If the save fails, the new name is kept on the phone and marked, never dropped.

## CSV import (fallback, no CRM needed)

Tap **Import CSV** and pick a `.csv` file with columns `business,phone,contact,notes` (extra columns are ignored; a file with no header row is read in that order). Rows whose phone matches a lead already in the loaded CRM queue are skipped as duplicates. Rows with no CRM match are queued as phone-only: after you call one, its outcome is saved on the phone only, not synced to the CRM.

## How it works (API calls)

Base URL: the CRM address you enter in Settings. Auth header on every call: `Authorization: Bearer <key>`. User-Agent: `WLTW-Dialer/1.0 (Android)`.

- **Load queue** — `GET /api/v1/listLeads?status=…&campaign=…&limit=100`. Answers `{"leads": [...]}` with `id`, `business`, `phone`, `name`, `address`, `notes`, `campaign`, `status`. A 401 means the key is wrong.
- **Previous calls** — `GET /api/v1/listCalls?lead_id=<id>`, newest first, each with `outcome`, `note`, `called_at`, `actor`. A 404 is treated as "no history yet" — no error loop.
- **Save outcome** — `POST /api/v1/recordCall`, JSON `{"lead_id": <id>, "outcome": "<value>"}`. Only a 200 advances to the next lead. Shift campaign adds `"to_campaign": "<picked campaign>"` with `"outcome": "shift_campaign"`.
- **List campaigns** — `GET /api/v1/campaigns`, answers `{"campaigns": […]}`. If this call fails, the shift picker falls back to `["delivery", "restaurant"]`.
- **Edit name** — `POST /api/v1/updateLead`, JSON `{"lead_id": <id>, "business": "<name>", "name": "<contact>"}`. Only the fields present are changed.

Current [muse-crm](https://github.com/We-Live-This-Way/muse-crm) implements `listLeads`, `listCalls`, `recordCall`, `campaigns`, and `updateLead`.

## Build from source

The Gradle project lives in `android/`. On a machine with JDK 17 and the Android SDK (compileSdk 34, minSdk 24):

```bash
cd android
gradle assembleDebug
```

A command-line script is also included:

```bash
cd android
./build-apk-manual.sh
```

The script expects a local Android SDK (`$HOME/Android/Sdk`) and may need its JDK / build-tools paths adjusted. Official release APKs on GitHub are signed with a dedicated release key kept off this repository. If you build your own APK with a different key, it will not install over the official release (uninstall first).

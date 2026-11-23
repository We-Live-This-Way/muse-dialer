package com.welivethisway.dialer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WLTW Dialer — assisted calling against the Lead Outreach CRM.
 *
 * Flow: Load queue (CRM) or Import CSV -> big CALL button opens the
 * system dialer with the number filled in (ACTION_DIAL only — the app
 * never places a call itself, the operator taps Call) -> back in the app,
 * pick a disposition -> it is posted to the CRM, and the next lead
 * appears ONLY after the CRM answers 200. If the post fails the lead
 * stays on screen with the error and a Retry button: a disposition
 * is never lost. An optional note can be typed on the disposition
 * panel; it is sent with the call (recordCall "note") under the same
 * no-loss rule — on failure the note stays in the field and Retry
 * sends the same note again. A lead that is clearly not a fit can
 * also be removed before calling, with the "Not qualified" button
 * under CALL — same post-and-advance-on-200 rule, one tap, no note.
 *
 * CSV leads with no matching CRM lead (matched by phone against the
 * loaded CRM queue) are local-only: their disposition is stored on
 * the phone and shown under "Saved on this phone only".
 *
 * A lead that belongs in a different campaign can be shifted
 * there instead of the delivery one, with the "Shift campaign"
 * button under CALL (pre-call) or the same option on the
 * disposition panel after a call. Either one opens a picker of
 * target campaigns (from GET /api/v1/campaigns, plus
 * "restaurant", minus the lead's current campaign); a
 * food-service name pre-selects "restaurant", but the operator can tap
 * any campaign. Picking one posts recordCall with outcome
 * "shift_campaign" and to_campaign set to the picked campaign,
 * under the same post-and-advance-on-200 rule as every other
 * disposition. The picker also has a "New campaign…" row at the
 * bottom: it opens a text field where the operator types a brand-new
 * campaign name, and Shift posts it exactly like a picked
 * campaign. The name must match the rule the CRM enforces — one
 * lowercase word, letters, numbers and hyphens, 1–30 characters,
 * starting and ending with a letter or digit — and the app
 * checks it before posting (a bad name shows an error on the
 * field and the dialog stays open). A successful shift to a
 * brand-new campaign adds it to the session's campaign cache,
 * so the next picker offers it (CRM leads only — a CSV-only
 * shift is saved on the phone, not in the CRM).
 *
 * "Edit name" sits on the lead card: the operator calls a lead whose
 * name he does not know, learns it on the call, and fixes it
 * right there. The dialog is pre-filled with the current
 * business and contact names (blank when unknown); Save posts
 * only the changed fields to POST /api/v1/updateLead. On
 * failure the new name is still kept on the phone and marked
 * clearly ("name edit saved on this phone only"), never
 * dropped — the same rule as dispositions. Editing never
 * advances the queue and never touches a disposition.
 *
 * Settings (CRM address, API key, campaign, status) live in
 * SharedPreferences on the phone. No key is baked into the app.
 */
public class MainActivity extends Activity {

    static final String PREFS = "wltw_dialer";
    static final String DEFAULT_BASE_URL = "https://crm.welivethisway.com";
    static final String DEFAULT_CAMPAIGN = "delivery";
    static final String DEFAULT_STATUS = "new";
    static final int QUEUE_LIMIT = 100;

    // Disposition buttons, in the order shown. Values are the exact
    // strings POSTed to /api/v1/recordCall.
    private static final String[] OUTCOME_LABELS = {
            "Interested", "Not interested", "No answer", "Voicemail",
            "Callback", "Wrong number", "Do not contact", "Not qualified",
            "Shift campaign"
    };
    private static final String[] OUTCOME_VALUES = {
            "interested", "not_interested", "no_answer", "voicemail",
            "callback", "wrong_number", "do_not_contact", "not_qualified",
            "shift_campaign"
    };
    private static final int[] OUTCOME_BUTTON_IDS = {
            R.id.btnDisp0, R.id.btnDisp1, R.id.btnDisp2, R.id.btnDisp3,
            R.id.btnDisp4, R.id.btnDisp5, R.id.btnDisp6, R.id.btnDisp7,
            R.id.btnDisp8
    };

    private static final int REQ_PICK_CSV = 101;
    private static final int REQ_QUEUE = 102;

    // Food-service keywords: a business name containing one of
    // these (case-insensitive substring) pre-selects "restaurant"
    // in the shift picker. The default is only a starting
    // highlight — the operator can tap any campaign.
    private static final String[] FOOD_KEYWORDS = {
            "restaurant", "cafe", "bakery", "pizza", "grill", "diner",
            "kitchen", "bistro", "deli", "eatery", "food", "bar", "pub",
            "taco", "sushi"
    };
    // Fallback target list for the shift picker when GET
    // /api/v1/campaigns fails or the endpoint is not live yet.
    private static final String[] FALLBACK_CAMPAIGNS = {"delivery", "restaurant"};

    // "New campaign…" row at the bottom of the shift picker.
    // Tapping it opens a text field instead of posting a shift;
    // the typed name must match the CRM's campaign naming rule
    // (the same rule the CRM enforces on recordCall): one
    // lowercase word, letters/numbers/hyphens, 1–30 chars,
    // starting and ending with a letter or digit.
    private static final String NEW_CAMPAIGN_ITEM = "New campaign…";
    private static final String NEW_CAMPAIGN_ERROR =
            "One lowercase word, letters, numbers and hyphens, up to 30 characters.";

    private static final int NAVY = Color.parseColor("#0d1f3c");
    private static final int TEAL = Color.parseColor("#0fb5ae");
    private static final int CREAM = Color.parseColor("#fff7e8");
    private static final int RED = Color.parseColor("#B00020");
    private static final int GREEN = Color.parseColor("#1B6B1B");

    private SharedPreferences prefs;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private final List<Lead> queue = new ArrayList<>();
    private int index = 0;
    private int calledCount = 0;

    // Set when CALL is tapped; onResume then shows the disposition
    // buttons. Stays true until a disposition is saved (or skipped).
    private boolean awaitingDisposition = false;
    private boolean posting = false;
    private Lead pendingLead = null;      // lead whose post failed
    private String pendingOutcome = null; // outcome to retry
    private String pendingNote = null;    // note to retry with (never dropped)
    private String pendingToCampaign = null; // target campaign for a shift retry

    // Campaigns from GET /api/v1/campaigns, cached for the session
    // (fetched when the shift picker first opens). Null until then.
    private List<String> campaignsCache = null;
    private boolean loadingCampaigns = false;

    // Previous calls per CRM lead id, as already fetched (formatted text
    // ready to show, or "" when the lead has no recorded calls). A fetch
    // that fails — e.g. the CRM predates listCalls — is stored as ""
    // too, so the app never nags the operator about it and never retries on
    // every render. Invalidated when a new call is saved for the lead.
    private final Map<Long, String> callHistoryCache = new HashMap<>();
    private long loadingHistoryFor = -1;

    private TextView tvProgress, tvQueueInfo, tvBusiness, tvPhone, tvAddress, tvContact,
            tvNotes, tvSource, tvSavedOutcome, tvPrevCalls, tvStatus, tvDispError, tvUnsyncedTitle;
    private EditText etNote;
    private LinearLayout layoutLead, layoutDisposition, layoutUnsynced;
    private Button btnCall, btnRetry, btnNotQualified, btnShiftCampaign, btnEditLead;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        tvProgress = findViewById(R.id.tvProgress);
        tvQueueInfo = findViewById(R.id.tvQueueInfo);
        tvBusiness = findViewById(R.id.tvBusiness);
        tvPhone = findViewById(R.id.tvPhone);
        tvAddress = findViewById(R.id.tvAddress);
        tvContact = findViewById(R.id.tvContact);
        tvNotes = findViewById(R.id.tvNotes);
        tvSource = findViewById(R.id.tvSource);
        tvSavedOutcome = findViewById(R.id.tvSavedOutcome);
        tvPrevCalls = findViewById(R.id.tvPrevCalls);
        tvStatus = findViewById(R.id.tvStatus);
        tvDispError = findViewById(R.id.tvDispError);
        tvUnsyncedTitle = findViewById(R.id.tvUnsyncedTitle);
        etNote = findViewById(R.id.etNote);
        layoutLead = findViewById(R.id.layoutLead);
        layoutDisposition = findViewById(R.id.layoutDisposition);
        layoutUnsynced = findViewById(R.id.layoutUnsynced);
        btnCall = findViewById(R.id.btnCall);
        btnRetry = findViewById(R.id.btnRetry);
        btnNotQualified = findViewById(R.id.btnNotQualified);
        btnShiftCampaign = findViewById(R.id.btnShiftCampaign);
        btnEditLead = findViewById(R.id.btnEditLead);

        for (int i = 0; i < OUTCOME_BUTTON_IDS.length; i++) {
            final String outcome = OUTCOME_VALUES[i];
            findViewById(OUTCOME_BUTTON_IDS[i]).setOnClickListener(v -> onDisposition(outcome));
        }
        btnCall.setOnClickListener(v -> callCurrent());
        // "Edit name": the operator just called a lead and learned who
        // they are — fix the business / contact name right on the
        // card. Never advances the queue, never posts a disposition.
        btnEditLead.setOnClickListener(v -> openEditNameDialog());
        // Pre-call "Not qualified": remove a lead that is clearly not
        // a fit (e.g. a restaurant) without calling it first, and
        // without misusing "Do not contact". Same post-and-advance
        // flow as the dispositions — the lead advances ONLY on HTTP
        // 200, and on failure it stays on screen with the error and
        // a Retry button.
        btnNotQualified.setOnClickListener(v -> onDisposition("not_qualified"));
        // Pre-call "Shift campaign": a lead that belongs in a
        // different campaign opens the picker instead of being
        // called for delivery. Picking a campaign posts the shift
        // under the same post-and-advance-on-200 rule as
        // "Not qualified" (no note, pre-call).
        btnShiftCampaign.setOnClickListener(v -> openShiftPicker());
        findViewById(R.id.btnDidntCall).setOnClickListener(v -> {
            awaitingDisposition = false;
            hideDisposition();
            status("No disposition saved — lead is still up.", true);
        });
        btnRetry.setOnClickListener(v -> {
            if (pendingLead != null && pendingOutcome != null) {
                postDisposition(pendingLead, pendingOutcome, pendingNote, pendingToCampaign);
            }
        });
        findViewById(R.id.btnLoad).setOnClickListener(v -> loadQueue());
        findViewById(R.id.btnImport).setOnClickListener(v -> pickCsv());
        findViewById(R.id.btnSkip).setOnClickListener(v -> skipCurrent());
        findViewById(R.id.btnPrev).setOnClickListener(v -> prevCurrent());
        findViewById(R.id.btnQueue).setOnClickListener(v ->
                startActivityForResult(new Intent(this, QueueActivity.class), REQ_QUEUE));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnClearQueue).setOnClickListener(v -> {
            queue.clear();
            index = 0;
            calledCount = 0;
            awaitingDisposition = false;
            hideDisposition();
            saveSession();
            render();
            status("Queue cleared. Load a queue or import a CSV to start.", true);
        });

        restoreSession();
        render();
        if (queue.isEmpty()) {
            status("No queue yet. Tap “Load queue” (needs your CRM API key in Settings) or “Import CSV”.", true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the system dialer: show the disposition buttons
        // for the lead that was just called.
        if (awaitingDisposition && currentLead() != null && !posting) {
            showDisposition();
        }
        renderUnsynced();
    }

    private Lead currentLead() {
        if (index < 0 || index >= queue.size()) return null;
        return queue.get(index);
    }

    // ---------- queue loading ----------

    private CrmApi apiOrNull() {
        String base = prefs.getString("base_url", DEFAULT_BASE_URL);
        String key = prefs.getString("api_key", "");
        if (base == null || base.trim().isEmpty()) {
            status("Enter the CRM address in Settings first.", false);
            return null;
        }
        if (key == null || key.isEmpty()) {
            status("Paste your CRM API key in Settings first (Settings → API key).", false);
            return null;
        }
        return new CrmApi(base, key);
    }

    private void loadQueue() {
        CrmApi api = apiOrNull();
        if (api == null) return;
        String statusFilter = prefs.getString("status", DEFAULT_STATUS);
        String campaign = prefs.getString("campaign", DEFAULT_CAMPAIGN);
        status("Loading leads from the CRM…", true);
        bg.execute(() -> {
            try {
                List<Lead> leads = api.listLeads(statusFilter, campaign, QUEUE_LIMIT);
                runOnUiThread(() -> {
                    queue.clear();
                    queue.addAll(leads);
                    index = 0;
                    calledCount = 0;
                    awaitingDisposition = false;
                    hideDisposition();
                    saveSession();
                    render();
                    status(leads.isEmpty()
                            ? "CRM returned no leads for status “" + statusFilter
                            + "” / campaign “" + campaign + "”. Check the filters in Settings."
                            : "Loaded " + leads.size() + " lead" + (leads.size() == 1 ? "" : "s")
                            + " from the CRM.", true);
                });
            } catch (CrmApi.ApiException e) {
                runOnUiThread(() -> status("Could not load the queue: " + e.getMessage(), false));
            }
        });
    }

    // ---------- CSV import ----------

    private void pickCsv() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("text/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(i, "Pick a CSV file"), REQ_PICK_CSV);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open a file picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_QUEUE) {
            // Back from the queue overview. If the operator tapped a lead,
            // jump to it (the overview may also have appended more
            // leads, so re-read the saved session first). A jump
            // never posts anything.
            if (resultCode == RESULT_OK && data != null) {
                restoreSession();
                int selected = data.getIntExtra(QueueActivity.EXTRA_SELECTED_INDEX, -1);
                if (selected >= 0 && selected < queue.size()) {
                    jumpTo(selected);
                    return;
                }
            }
            restoreSession();
            render();
            return;
        }
        if (requestCode != REQ_PICK_CSV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        bg.execute(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try (InputStream in = getContentResolver().openInputStream(uri);
                     BufferedReader r = new BufferedReader(
                             new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                List<Lead> imported = CsvParser.parseLeads(sb.toString());
                runOnUiThread(() -> mergeCsv(imported));
            } catch (Exception e) {
                runOnUiThread(() -> status("Could not read that CSV: " + e.getMessage(), false));
            }
        });
    }

    /** Add CSV rows to the queue. A row whose phone matches a lead
     *  already in the queue is skipped (it is already there — if that
     *  lead came from the CRM its disposition posts to the CRM).
     *  Rows with no match are added as phone-only leads. */
    private void mergeCsv(List<Lead> imported) {
        int matched = 0, added = 0, skipped = 0;
        for (Lead row : imported) {
            String norm = Lead.normalizePhone(row.phone);
            Lead existing = null;
            for (Lead q : queue) {
                if (Lead.normalizePhone(q.phone).equals(norm)) { existing = q; break; }
            }
            if (existing != null) {
                if (existing.crmId != null) matched++; // already in the CRM queue
                else skipped++; // duplicate of a CSV row already queued
                continue;
            }
            queue.add(row);
            added++;
        }
        if (queue.isEmpty()) index = 0;
        saveSession();
        render();
        if (imported.isEmpty()) {
            status("That CSV had no usable rows — it needs business,phone,contact columns and a phone on each row.", false);
        } else {
            status("CSV: " + imported.size() + " row" + (imported.size() == 1 ? "" : "s")
                    + " read, " + added + " added to the queue"
                    + (matched > 0 ? ", " + matched + " already in the CRM queue" : "")
                    + (skipped > 0 ? ", " + skipped + " skipped" : "")
                    + ". Rows with no CRM match are phone-only — their outcome is saved on this phone.",
                    true);
        }
    }

    // ---------- calling ----------

    /** Assisted dial only: opens the system dialer with the number
     *  filled in. The app never places a call by itself (no
     *  ACTION_CALL, no CALL_PHONE permission). */
    private void callCurrent() {
        Lead lead = currentLead();
        if (lead == null) return;
        String uri = lead.dialUri();
        if (uri.equals("tel:") || uri.equals("tel:+")) {
            status("This lead has no usable phone number.", false);
            return;
        }
        try {
            awaitingDisposition = true;
            Intent dial = new Intent(Intent.ACTION_DIAL, Uri.parse(uri));
            startActivity(dial);
        } catch (Exception e) {
            awaitingDisposition = false;
            status("Could not open the dialer: " + e.getMessage(), false);
        }
    }

    private void skipCurrent() {
        if (currentLead() == null) return;
        awaitingDisposition = false;
        hideDisposition();
        index++;
        saveSession();
        render();
        status("Skipped — no disposition saved for that lead.", true);
    }

    /** Step back to the lead before the current one. Never posts
     *  anything and never touches a saved disposition — it only
     *  moves the current position, like Skip in reverse. */
    private void prevCurrent() {
        if (currentLead() == null) return;
        if (index <= 0) {
            status("Already at the first lead.", true);
            return;
        }
        awaitingDisposition = false;
        hideDisposition();
        index--;
        saveSession();
        render();
    }

    /** Jump to a lead picked in the queue overview. Never posts
     *  anything — it only moves the current position. If a
     *  disposition panel was open for the previous lead, it is
     *  closed with nothing saved, exactly like Skip. */
    private void jumpTo(int position) {
        index = position;
        awaitingDisposition = false;
        hideDisposition();
        saveSession();
        render();
        status("Jumped to lead " + (index + 1) + " of " + queue.size() + ".", true);
    }

    // ---------- dispositions ----------

    private void onDisposition(String outcome) {
        Lead lead = currentLead();
        if (lead == null || posting) return;
        if ("shift_campaign".equals(outcome)) {
            // "Shift campaign" does not post straight away: the operator
            // picks the target campaign in the picker first.
            openShiftPicker();
            return;
        }
        // The note lives on the disposition panel (shown after a
        // call). The pre-call "Not qualified" button is one-tap with
        // no note: the panel is hidden when it is tapped, so the
        // note is null there by construction.
        String note = null;
        if (layoutDisposition.getVisibility() == View.VISIBLE) {
            note = etNote.getText().toString().trim();
            if (note.isEmpty()) note = null;
        }
        if (lead.crmId != null) {
            postDisposition(lead, outcome, note, null);
        } else {
            // CSV-only lead: no CRM record to post to. Store the
            // outcome (and the note, if any) on the phone and say
            // so clearly.
            saveUnsynced(lead, outcome, note);
            lead.disposition = labelFor(outcome);
            calledCount++;
            index++;
            awaitingDisposition = false;
            pendingLead = null;
            pendingOutcome = null;
            hideDisposition();
            saveSession();
            render();
            status("Saved on this phone only — not in the CRM (no matching CRM lead for "
                    + lead.phone + ").", true);
        }
    }

    /** Target campaign for a disposition that moves the lead to
     *  another campaign, or null for an ordinary outcome. Only
     *  "shift_campaign" carries one, and it is the campaign the operator
     *  picked in the picker — never hardcoded. The picked target
     *  is kept with the pending outcome, so Retry re-sends the
     *  same target. */
    private static String toCampaignFor(String outcome, String pickedCampaign) {
        return "shift_campaign".equals(outcome) ? pickedCampaign : null;
    }

    /** "Shift campaign" picker, shared by the pre-call button and
     *  the post-call disposition. The list is fetched from GET
     *  /api/v1/campaigns when the picker first opens and cached
     *  for the session; if the call fails (or the endpoint is not
     *  live yet) it falls back to ["delivery", "restaurant"].
     *  No key / no CRM address yet falls back the same way — the
     *  missing key is surfaced by the post itself, with Retry,
     *  like every other disposition. */
    private void openShiftPicker() {
        Lead lead = currentLead();
        if (lead == null || posting || loadingCampaigns) return;
        if (campaignsCache != null) {
            showShiftPicker(lead, campaignsCache);
            return;
        }
        CrmApi api = apiOrNullSilent();
        if (api == null) {
            showShiftPicker(lead, fallbackCampaigns());
            return;
        }
        loadingCampaigns = true;
        status("Loading campaigns…", true);
        bg.execute(() -> {
            List<String> fetched = null;
            try {
                fetched = api.listCampaigns();
            } catch (Exception ignored) {
                // Endpoint not live yet, or offline — fall back.
            }
            final List<String> campaigns =
                    (fetched == null || fetched.isEmpty()) ? fallbackCampaigns() : fetched;
            runOnUiThread(() -> {
                loadingCampaigns = false;
                campaignsCache = campaigns;
                // Only show it if the operator is still on that lead.
                if (currentLead() == lead) showShiftPicker(lead, campaigns);
            });
        });
    }

    /** Build the picker list from the fetched campaigns: plus
     *  "restaurant" if the CRM does not list it, minus the lead's
     *  current campaign — shifting to where it already is is
     *  meaningless. A food-service name pre-selects "restaurant";
     *  otherwise the first campaign is pre-selected. The default
     *  is only a starting highlight — tapping any row picks that
     *  campaign and posts the shift. The "New campaign…" row is
     *  always appended last, even when the lead is already on the
     *  only known campaign, so there is always something to shift
     *  to — no "Nothing to shift to" early return. Tapping it
     *  opens a text field for a brand-new campaign name instead
     *  of posting. */
    private void showShiftPicker(Lead lead, List<String> campaigns) {
        List<String> targets = new ArrayList<>();
        for (String c : campaigns) {
            if (c == null) continue;
            String t = c.trim();
            if (t.isEmpty()) continue;
            if (t.equalsIgnoreCase(lead.campaign)) continue;
            if (!containsIgnoreCase(targets, t)) targets.add(t);
        }
        if (!containsIgnoreCase(targets, "restaurant")
                && !"restaurant".equalsIgnoreCase(lead.campaign)) {
            targets.add("restaurant");
        }
        targets.add(NEW_CAMPAIGN_ITEM);
        int checked = 0;
        if (looksLikeFoodService(lead.business) && containsIgnoreCase(targets, "restaurant")) {
            checked = indexIgnoreCase(targets, "restaurant");
        }
        final String[] items = targets.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Shift to which campaign?")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (NEW_CAMPAIGN_ITEM.equals(items[which])) {
                        openNewCampaignDialog(lead);
                    } else {
                        submitShift(lead, items[which]);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Campaign naming rule, matching the one the CRM enforces on
     *  recordCall's to_campaign: one lowercase word, letters,
     *  numbers and hyphens, 1–30 characters, starting and ending
     *  with a letter or digit. The caller trims and lowercases
     *  the input first. */
    private static boolean isValidCampaignName(String name) {
        return name != null && name.matches("[a-z0-9]([a-z0-9-]{0,28}[a-z0-9])?");
    }

    /** Text field for a brand-new campaign name, from the picker's
     *  "New campaign…" row. The name is trimmed and lowercased,
     *  then checked against the CRM's naming rule; an invalid
     *  name does NOT dismiss the dialog — the error shows on the
     *  field so the operator can correct it. A valid name posts exactly
     *  like a picked campaign, via submitShift(). */
    private void openNewCampaignDialog(Lead lead) {
        final EditText input = new EditText(this);
        input.setHint("pharmacy");
        input.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New campaign")
                .setView(input)
                .setPositiveButton("Shift", null) // overridden below: no dismiss on bad input
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim().toLowerCase(Locale.US);
                    if (!isValidCampaignName(name)) {
                        input.setError(NEW_CAMPAIGN_ERROR);
                        return;
                    }
                    dialog.dismiss();
                    submitShift(lead, name);
                }));
        dialog.show();
    }

    /** Post the picked shift. The note, if the disposition panel
     *  is showing, goes with the post — same as every other
     *  disposition. A CSV-only lead has no CRM record to move, so
     *  the shift is saved on the phone instead, with the picked
     *  campaign named. */
    private void submitShift(Lead lead, String toCampaign) {
        if (lead != currentLead()) return; // lead changed while picking
        String note = null;
        if (layoutDisposition.getVisibility() == View.VISIBLE) {
            note = etNote.getText().toString().trim();
            if (note.isEmpty()) note = null;
        }
        if (lead.crmId != null) {
            postDisposition(lead, "shift_campaign", note, toCampaign);
        } else {
            saveUnsynced(lead, "shift_campaign", note, toCampaign);
            lead.disposition = labelFor("shift_campaign") + " → " + toCampaign;
            calledCount++;
            index++;
            awaitingDisposition = false;
            pendingLead = null;
            pendingOutcome = null;
            pendingToCampaign = null;
            hideDisposition();
            saveSession();
            render();
            status("Saved on this phone only — not in the CRM (no matching CRM lead for "
                    + lead.phone + ").", true);
        }
    }

    // ---------- edit name ----------

    /** "Edit name": the operator just called a lead and learned who they
     *  are. The dialog is pre-filled with the lead's current
     *  business and contact names (blank when unknown) — he fixes
     *  what he learned and taps Save. Only fields that actually
     *  changed are posted, so an untouched save does nothing.
     *  A CSV-only lead has no CRM record: the edit is kept on the
     *  phone, like every other CSV-only change. A CRM lead posts
     *  to /api/v1/updateLead; on failure the new name is still
     *  kept on the phone and marked clearly (see
     *  saveUnsyncedNameEdit), never dropped — the same rule as
     *  dispositions. Editing never advances the queue and never
     *  touches a disposition. */
    private void openEditNameDialog() {
        Lead lead = currentLead();
        if (lead == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        final EditText etBusiness = new EditText(this);
        etBusiness.setHint("Business name");
        etBusiness.setSingleLine(true);
        etBusiness.setText(lead.business == null ? "" : lead.business);
        final EditText etContact = new EditText(this);
        etContact.setHint("Contact name");
        etContact.setSingleLine(true);
        etContact.setText(lead.contact == null ? "" : lead.contact);
        box.addView(etBusiness);
        box.addView(etContact);
        new AlertDialog.Builder(this)
                .setTitle("Edit name")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newBusiness = etBusiness.getText().toString().trim();
                    String newContact = etContact.getText().toString().trim();
                    String oldBusiness = lead.business == null ? "" : lead.business.trim();
                    String oldContact = lead.contact == null ? "" : lead.contact.trim();
                    boolean businessChanged = !newBusiness.equals(oldBusiness);
                    boolean contactChanged = !newContact.equals(oldContact);
                    if (!businessChanged && !contactChanged) {
                        status("No changes to save.", true);
                        return;
                    }
                    saveNameEdit(lead,
                            businessChanged ? newBusiness : null,
                            contactChanged ? newContact : null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Apply a name edit. business/contact are null when that field
     *  did not change. A CSV-only lead is updated on the phone
     *  only. A CRM lead posts to updateLead: on HTTP 200 the card
     *  updates and any stale phone-only edit for this lead is
     *  cleared; on failure the new name still lands on the card
     *  and in the saved queue, plus a phone-only marker, so the
     *  edit is never lost and the operator can see it did not reach the
     *  CRM. This never advances the queue or touches a disposition. */
    private void saveNameEdit(Lead lead, String newBusiness, String newContact) {
        if (lead.crmId == null) {
            if (newBusiness != null) lead.business = newBusiness;
            if (newContact != null) lead.contact = newContact;
            saveSession();
            render();
            status("Saved on this phone (CSV lead — not in the CRM).", true);
            return;
        }
        CrmApi api = apiOrNullSilent();
        if (api == null) {
            // No key / no CRM address: keep it on the phone and mark
            // it, exactly like a failed post below.
            applyLocalNameEdit(lead, newBusiness, newContact);
            status("No CRM key in Settings — name saved on this phone only, not in the CRM.", false);
            return;
        }
        final long id = lead.crmId;
        bg.execute(() -> {
            try {
                api.updateLead(id, newBusiness, newContact);
                runOnUiThread(() -> {
                    if (newBusiness != null) lead.business = newBusiness;
                    if (newContact != null) lead.contact = newContact;
                    clearUnsyncedNameEdits(lead);
                    saveSession();
                    render();
                    status("Saved in the CRM: "
                            + (lead.business == null || lead.business.isEmpty()
                                    ? lead.phone : lead.business), true);
                });
            } catch (CrmApi.ApiException e) {
                runOnUiThread(() -> {
                    applyLocalNameEdit(lead, newBusiness, newContact);
                    status("Could not reach the CRM — name saved on this phone only, not in the CRM. ("
                            + e.getMessage() + ")", false);
                });
            }
        });
    }

    /** Keep a name edit on the phone after the CRM post failed (or
     *  there was no key): update the card and the saved queue, and
     *  add a phone-only marker so the operator can see it never reached
     *  the CRM. */
    private void applyLocalNameEdit(Lead lead, String newBusiness, String newContact) {
        if (newBusiness != null) lead.business = newBusiness;
        if (newContact != null) lead.contact = newContact;
        saveUnsyncedNameEdit(lead);
        saveSession();
        render();
    }

    /** A phone-only name edit, listed with the other unsynced items
     *  ("Saved on this phone only — not in the CRM"). */
    private void saveUnsyncedNameEdit(Lead lead) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("unsynced", "[]"));
            JSONObject o = new JSONObject();
            o.put("business", lead.business);
            o.put("phone", lead.phone);
            o.put("contact", lead.contact);
            o.put("outcome", "name_edit");
            o.put("outcome_label", "Name edit — not in the CRM");
            o.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            arr.put(o);
            prefs.edit().putString("unsynced", arr.toString()).apply();
        } catch (Exception ignored) {}
        renderUnsynced();
    }

    /** Does this lead have a phone-only name edit waiting? Used to
     *  mark the lead card's source line. */
    private boolean hasUnsyncedNameEdit(Lead lead) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("unsynced", "[]"));
            String norm = Lead.normalizePhone(lead.phone);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if ("name_edit".equals(o.optString("outcome", ""))
                        && Lead.normalizePhone(o.optString("phone", "")).equals(norm)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Drop the phone-only name-edit markers for this lead — used
     *  after a successful CRM save, so a stale marker never claims
     *  the name is still unsynced. */
    private void clearUnsyncedNameEdits(Lead lead) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("unsynced", "[]"));
            String norm = Lead.normalizePhone(lead.phone);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if ("name_edit".equals(o.optString("outcome", ""))
                        && Lead.normalizePhone(o.optString("phone", "")).equals(norm)) {
                    continue;
                }
                kept.put(o);
            }
            prefs.edit().putString("unsynced", kept.toString()).apply();
        } catch (Exception ignored) {}
        renderUnsynced();
    }

    private static List<String> fallbackCampaigns() {
        return new ArrayList<>(Arrays.asList(FALLBACK_CAMPAIGNS));
    }

    /** Case-insensitive substring match against the food-service
     *  keywords — a "Joe's Pizza" or "Cafe Luna" pre-selects
     *  "restaurant" in the shift picker. */
    private static boolean looksLikeFoodService(String business) {
        if (business == null) return false;
        String name = business.toLowerCase(Locale.US);
        for (String kw : FOOD_KEYWORDS) {
            if (name.contains(kw)) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        return indexIgnoreCase(list, value) >= 0;
    }

    private static int indexIgnoreCase(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(value)) return i;
        }
        return -1;
    }

    private void postDisposition(Lead lead, String outcome, String note, String toCampaign) {
        CrmApi api = apiOrNull();
        if (api == null) {
            // No key / no CRM address: do not advance. The lead stays
            // on screen so the disposition can be retried. If the
            // disposition panel is hidden (pre-call "Not qualified"
            // or "Shift campaign"), show it so the error and
            // Retry are visible. The note is kept with the pending
            // outcome, never dropped.
            if (layoutDisposition.getVisibility() != View.VISIBLE) {
                layoutDisposition.setVisibility(View.VISIBLE);
                btnCall.setVisibility(View.GONE);
                btnNotQualified.setVisibility(View.GONE);
                btnShiftCampaign.setVisibility(View.GONE);
            }
            tvDispError.setText("Cannot save yet: " + tvStatus.getText());
            tvDispError.setVisibility(View.VISIBLE);
            btnRetry.setVisibility(View.VISIBLE);
            pendingLead = lead;
            pendingOutcome = outcome;
            pendingNote = note;
            pendingToCampaign = toCampaign;
            return;
        }
        posting = true;
        setDispositionEnabled(false);
        tvDispError.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
        status("Saving outcome…", true);
        bg.execute(() -> {
            try {
                api.recordCall(lead.crmId, outcome, note, toCampaignFor(outcome, toCampaign));
                runOnUiThread(() -> {
                    posting = false;
                    setDispositionEnabled(true);
                    lead.disposition = "shift_campaign".equals(outcome) && toCampaign != null
                            ? labelFor(outcome) + " → " + toCampaign
                            : labelFor(outcome);
                    // A shift to a brand-new campaign teaches the
                    // session's picker: offer it next time. CRM
                    // leads only — this method is only ever called
                    // for CRM leads (a CSV-only shift is saved on
                    // the phone and never reaches the CRM, so its
                    // campaign is not a real CRM campaign).
                    if ("shift_campaign".equals(outcome) && toCampaign != null
                            && campaignsCache != null
                            && !containsIgnoreCase(campaignsCache, toCampaign)) {
                        campaignsCache.add(toCampaign);
                    }
                    // The saved call (and its note) is now part of this
                    // lead's history — drop the cached read-back so the
                    // next visit to this lead shows it.
                    callHistoryCache.remove(lead.crmId);
                    calledCount++;
                    index++;
                    awaitingDisposition = false;
                    pendingLead = null;
                    pendingOutcome = null;
                    pendingNote = null;
                    pendingToCampaign = null;
                    hideDisposition();
                    saveSession();
                    render();
                    status("Saved: " + labelFor(outcome) + " — next lead.", true);
                });
            } catch (CrmApi.ApiException e) {
                runOnUiThread(() -> {
                    posting = false;
                    setDispositionEnabled(true);
                    // Stay on this lead. The error and Retry are shown
                    // in the disposition panel; nothing is lost — the
                    // note stays in the field and in pendingNote, so
                    // Retry sends the same note again, and
                    // pendingToCampaign keeps the picked target, so a
                    // shift Retry re-sends the same campaign. If the
                    // panel is hidden (pre-call "Not qualified" or
                    // "Shift campaign"), show it so the error and
                    // Retry are visible.
                    if (layoutDisposition.getVisibility() != View.VISIBLE) {
                        layoutDisposition.setVisibility(View.VISIBLE);
                        btnCall.setVisibility(View.GONE);
                        btnNotQualified.setVisibility(View.GONE);
                        btnShiftCampaign.setVisibility(View.GONE);
                    }
                    tvDispError.setText("Not saved: " + e.getMessage());
                    tvDispError.setVisibility(View.VISIBLE);
                    btnRetry.setVisibility(View.VISIBLE);
                    pendingLead = lead;
                    pendingOutcome = outcome;
                    pendingNote = note;
                    pendingToCampaign = toCampaign;
                    status("Outcome NOT saved — the lead is still on screen. Fix the problem and tap Retry.", false);
                });
            }
        });
    }

    private String labelFor(String outcome) {
        for (int i = 0; i < OUTCOME_VALUES.length; i++) {
            if (OUTCOME_VALUES[i].equals(outcome)) return OUTCOME_LABELS[i];
        }
        return outcome;
    }

    private void setDispositionEnabled(boolean enabled) {
        for (int id : OUTCOME_BUTTON_IDS) findViewById(id).setEnabled(enabled);
        findViewById(R.id.btnDidntCall).setEnabled(enabled);
        btnNotQualified.setEnabled(enabled);
        btnShiftCampaign.setEnabled(enabled);
        etNote.setEnabled(enabled);
    }

    private void showDisposition() {
        layoutDisposition.setVisibility(View.VISIBLE);
        btnCall.setVisibility(View.GONE);
        btnNotQualified.setVisibility(View.GONE);
        btnShiftCampaign.setVisibility(View.GONE);
    }

    private void hideDisposition() {
        layoutDisposition.setVisibility(View.GONE);
        tvDispError.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
        // The note belongs to the disposition just saved or abandoned,
        // so a fresh panel starts empty. (The failure path never calls
        // this — it shows the panel instead, so the note survives.)
        etNote.setText("");
        btnCall.setVisibility(currentLead() == null ? View.GONE : View.VISIBLE);
        btnNotQualified.setVisibility(currentLead() == null ? View.GONE : View.VISIBLE);
        btnShiftCampaign.setVisibility(currentLead() == null ? View.GONE : View.VISIBLE);
    }

    // ---------- unsynced (phone-only) dispositions ----------

    private void saveUnsynced(Lead lead, String outcome, String note) {
        saveUnsynced(lead, outcome, note, null);
    }

    /** Same, with the picked target campaign for a shift — the
     *  saved label then reads "Shift campaign → restaurant". */
    private void saveUnsynced(Lead lead, String outcome, String note, String toCampaign) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("unsynced", "[]"));
            JSONObject o = new JSONObject();
            o.put("business", lead.business);
            o.put("phone", lead.phone);
            o.put("contact", lead.contact);
            o.put("outcome", outcome);
            o.put("outcome_label", "shift_campaign".equals(outcome) && toCampaign != null
                    ? labelFor(outcome) + " → " + toCampaign : labelFor(outcome));
            if (toCampaign != null && !toCampaign.isEmpty()) o.put("to_campaign", toCampaign);
            if (note != null && !note.isEmpty()) o.put("note", note);
            o.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            arr.put(o);
            prefs.edit().putString("unsynced", arr.toString()).apply();
        } catch (Exception ignored) {}
        renderUnsynced();
    }

    private void renderUnsynced() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("unsynced", "[]"));
            layoutUnsynced.removeAllViews();
            if (arr.length() == 0) {
                tvUnsyncedTitle.setVisibility(View.GONE);
                return;
            }
            tvUnsyncedTitle.setVisibility(View.VISIBLE);
            tvUnsyncedTitle.setText("Saved on this phone only — not in the CRM (" + arr.length() + "):");
            int shown = 0;
            for (int i = arr.length() - 1; i >= 0 && shown < 20; i--, shown++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                TextView t = new TextView(this);
                String biz = o.optString("business", "");
                String line = (biz.isEmpty() ? o.optString("phone", "") : biz + " — " + o.optString("phone", ""))
                        + " · " + o.optString("outcome_label", o.optString("outcome", ""))
                        + " · " + o.optString("time", "");
                String note = o.optString("note", "");
                if (!note.isEmpty()) line += "\n“" + note + "”";
                t.setText(line);
                t.setTextSize(13);
                t.setTextColor(Color.DKGRAY);
                t.setPadding(0, 4, 0, 4);
                layoutUnsynced.addView(t);
            }
        } catch (Exception ignored) {}
    }

    // ---------- rendering + session persistence ----------

    private void render() {
        int left = Math.max(0, queue.size() - index);
        tvProgress.setText("Called: " + calledCount + "  ·  Left: " + left);
        Lead lead = currentLead();
        if (lead == null) {
            layoutLead.setVisibility(View.GONE);
            btnCall.setVisibility(View.GONE);
            btnNotQualified.setVisibility(View.GONE);
            btnShiftCampaign.setVisibility(View.GONE);
            layoutDisposition.setVisibility(View.GONE);
            tvQueueInfo.setText(queue.isEmpty()
                    ? "Queue is empty."
                    : "Queue done — " + calledCount + " called this session.");
            return;
        }
        layoutLead.setVisibility(View.VISIBLE);
        btnCall.setVisibility(View.VISIBLE);
        btnNotQualified.setVisibility(View.VISIBLE);
        btnShiftCampaign.setVisibility(View.VISIBLE);
        tvQueueInfo.setText("Lead " + (index + 1) + " of " + queue.size()
                + (queue.size() > 1 ? "  ·  " : ""));
        tvBusiness.setText(lead.business == null || lead.business.isEmpty()
                ? "(no business name)" : lead.business);
        tvPhone.setText(lead.phone);
        tvAddress.setText(lead.address == null || lead.address.isEmpty()
                ? "" : lead.address);
        tvAddress.setVisibility(lead.address == null || lead.address.isEmpty()
                ? View.GONE : View.VISIBLE);
        tvContact.setText(lead.contact == null || lead.contact.isEmpty()
                ? "" : "Contact: " + lead.contact);
        tvContact.setVisibility(lead.contact == null || lead.contact.isEmpty()
                ? View.GONE : View.VISIBLE);
        tvNotes.setText(lead.notes == null || lead.notes.isEmpty()
                ? "" : lead.notes);
        tvNotes.setVisibility(lead.notes == null || lead.notes.isEmpty()
                ? View.GONE : View.VISIBLE);
        if (lead.crmId != null) {
            String src = "From the CRM" + (lead.campaign != null && !lead.campaign.isEmpty()
                    ? " · " + lead.campaign : "");
            if (hasUnsyncedNameEdit(lead)) {
                // The name on this card was edited on the phone and
                // never reached the CRM — say so on the card itself,
                // not just in the unsynced list at the bottom.
                tvSource.setText(src + " · name edit saved on this phone only");
                tvSource.setTextColor(RED);
            } else {
                tvSource.setText(src);
                tvSource.setTextColor(Color.DKGRAY);
            }
        } else {
            tvSource.setText("CSV only — not in the CRM. The outcome will be saved on this phone, not synced.");
            tvSource.setTextColor(RED);
        }
        // A lead that already has a saved outcome (jumped back to
        // from the queue overview) says so, so the operator knows a new
        // call + disposition would save ANOTHER record, not replace
        // the one already saved.
        if (lead.disposition != null && !lead.disposition.isEmpty()) {
            tvSavedOutcome.setText("✓ Already saved: " + lead.disposition
                    + " — call again and pick a new outcome to save another record.");
            tvSavedOutcome.setVisibility(View.VISIBLE);
        } else {
            tvSavedOutcome.setVisibility(View.GONE);
        }
        // Previous calls + notes, read back from the CRM so the operator sees
        // what he wrote last time before calling again. Fetched once
        // per lead (cached); a failed fetch just means nothing shows.
        showPreviousCalls(lead);
        renderUnsynced();
    }

    /** Show the cached call history for this lead, or fetch it from
     *  the CRM in the background and show it when it arrives. */
    private void showPreviousCalls(Lead lead) {
        if (lead.crmId == null) {
            tvPrevCalls.setVisibility(View.GONE);
            return;
        }
        String cached = callHistoryCache.get(lead.crmId);
        if (cached != null) {
            if (cached.isEmpty()) tvPrevCalls.setVisibility(View.GONE);
            else {
                tvPrevCalls.setText(cached);
                tvPrevCalls.setVisibility(View.VISIBLE);
            }
            return;
        }
        tvPrevCalls.setVisibility(View.GONE);
        if (loadingHistoryFor == lead.crmId) return; // already fetching
        loadingHistoryFor = lead.crmId;
        final long wanted = lead.crmId;
        CrmApi api = apiOrNullSilent();
        if (api == null) {
            loadingHistoryFor = -1;
            callHistoryCache.put(wanted, "");
            return;
        }
        bg.execute(() -> {
            String text = "";
            try {
                List<CrmApi.CallRecord> calls = api.listCalls(wanted);
                if (!calls.isEmpty()) {
                    StringBuilder sb = new StringBuilder("Previous calls:");
                    int shown = 0;
                    for (CrmApi.CallRecord c : calls) {
                        if (shown >= 3) break; // newest 3 is enough on screen
                        shown++;
                        sb.append("\n• ");
                        if (c.calledAt != null && c.calledAt.length() >= 10)
                            sb.append(c.calledAt.substring(0, 10)).append(" — ");
                        sb.append(labelFor(c.outcome));
                        if (c.note != null && !c.note.isEmpty())
                            sb.append(" — “").append(c.note).append("”");
                    }
                    text = sb.toString();
                }
            } catch (Exception ignored) {
                // No history available (old CRM, offline, bad key) —
                // the lead still shows fine, just without old notes.
            }
            final String result = text;
            runOnUiThread(() -> {
                callHistoryCache.put(wanted, result);
                loadingHistoryFor = -1;
                // Only show it if the operator is still on that lead.
                Lead now = currentLead();
                if (now != null && now.crmId != null && now.crmId == wanted
                        && !result.isEmpty()) {
                    tvPrevCalls.setText(result);
                    tvPrevCalls.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    /** Like apiOrNull() but without writing to the status line — the
     *  history fetch is a quiet background read, not a user action. */
    private CrmApi apiOrNullSilent() {
        String base = prefs.getString("base_url", DEFAULT_BASE_URL);
        String key = prefs.getString("api_key", "");
        if (base == null || base.trim().isEmpty()) return null;
        if (key == null || key.isEmpty()) return null;
        return new CrmApi(base, key);
    }

    private void status(String msg, boolean ok) {
        if (msg != null && msg.length() > 300) msg = msg.substring(0, 300) + "…";
        tvStatus.setText(msg);
        tvStatus.setTextColor(ok ? GREEN : RED);
    }

    private void saveSession() {
        try {
            JSONArray arr = new JSONArray();
            for (Lead l : queue) arr.put(l.toJson());
            prefs.edit()
                    .putString("queue", arr.toString())
                    .putInt("queue_index", index)
                    .putInt("called_count", calledCount)
                    .apply();
        } catch (Exception ignored) {}
    }

    private void restoreSession() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("queue", "[]"));
            queue.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) queue.add(Lead.fromSavedJson(o));
            }
            index = prefs.getInt("queue_index", 0);
            if (index < 0 || index > queue.size()) index = 0;
            calledCount = prefs.getInt("called_count", 0);
        } catch (Exception ignored) {
            queue.clear();
            index = 0;
            calledCount = 0;
        }
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }
}

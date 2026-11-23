package com.welivethisway.dialer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Queue overview — the whole loaded queue in one list, in the same
 * order the dialer steps through it, so the operator can pick which lead to
 * call instead of only stepping forward one by one.
 *
 * Each row shows the business, the phone, the position in the queue,
 * a marker on the current lead, and the outcome already saved for
 * leads that were dispositioned. The list opens scrolled to the
 * current lead. Tapping a row jumps the main screen to that lead —
 * including a lead that already has an outcome (it can be called
 * again; a new call + disposition posts a NEW record, nothing is
 * ever re-posted just by jumping).
 *
 * The search box filters by business name or phone (substring,
 * case-insensitive — "pharmacy" shows only the pharmacies). "Load
 * more" fetches the next page from the CRM (offset = CRM leads
 * already loaded that still match the active filter — a lead whose
 * saved disposition moved it out of the filter no longer counts,
 * so the next page never skips a row) and appends it in the same
 * order.
 *
 * A "Sort" button switches the view between "Dialer order" (the
 * order the dialer steps through, as loaded) and "Campaign"
 * (grouped by campaign, alphabetical, dialer order kept inside
 * each campaign). Sorting only reorders the rows shown here — it
 * never re-fetches and never changes the dialer's stepping order.
 * Each row's subtitle shows the lead's campaign.
 *
 * The queue lives in the same SharedPreferences session the main
 * screen uses, so this screen always sees the same list — and
 * "Load more" writes the appended leads back there too.
 */
public class QueueActivity extends Activity {

    public static final String EXTRA_SELECTED_INDEX = "selected_index";

    private static final int NAVY = Color.parseColor("#0d1f3c");
    private static final int CURRENT_BG = Color.parseColor("#d8f0ee");
    private static final int DONE_GREEN = Color.parseColor("#1B6B1B");
    private static final int GRAY = Color.DKGRAY;

    // Sort modes for the queue view only — the dialer's stepping
    // order (MainActivity) is never changed by this.
    private static final String PREF_SORT = "queue_sort";
    private static final String SORT_DIALER = "dialer";
    private static final String SORT_CAMPAIGN = "campaign";

    private SharedPreferences prefs;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private final List<Lead> queue = new ArrayList<>();
    private int currentIndex = 0;
    /** Queue positions matching the current search, in display
     *  order (dialer order, or campaign-sorted when that sort is
     *  on). Taps and the current-lead highlight use these indices,
     *  never list positions. */
    private final List<Integer> shown = new ArrayList<>();
    private String query = "";
    private String sortMode = SORT_DIALER;

    private TextView tvTitle, tvStatus;
    private EditText etSearch;
    private ListView lvQueue;
    private Button btnLoadMore, btnLoadAll, btnSort;
    private QueueAdapter adapter;
    private boolean loadingMore = false;
    private boolean loadingAll = false;
    private boolean noMore = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_queue);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        tvTitle = findViewById(R.id.tvQueueTitle);
        tvStatus = findViewById(R.id.tvQueueStatus);
        etSearch = findViewById(R.id.etSearch);
        lvQueue = findViewById(R.id.lvQueue);
        btnLoadMore = findViewById(R.id.btnLoadMore);
        btnLoadAll = findViewById(R.id.btnLoadAll);
        btnSort = findViewById(R.id.btnSort);

        // Sort choice persists across opens; anything but "campaign"
        // falls back to dialer order.
        sortMode = prefs.getString(PREF_SORT, SORT_DIALER);
        if (!SORT_CAMPAIGN.equals(sortMode)) sortMode = SORT_DIALER;
        updateSortButton();
        btnSort.setOnClickListener(v -> {
            sortMode = SORT_CAMPAIGN.equals(sortMode) ? SORT_DIALER : SORT_CAMPAIGN;
            prefs.edit().putString(PREF_SORT, sortMode).apply();
            updateSortButton();
            applyFilter(false);
        });

        restoreQueue();
        adapter = new QueueAdapter();
        lvQueue.setAdapter(adapter);
        applyFilter(true);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                applyFilter(false);
            }
        });

        lvQueue.setOnItemClickListener((parent, view, position, id) -> {
            int selected = shown.get(position);
            Intent result = new Intent();
            result.putExtra(EXTRA_SELECTED_INDEX, selected);
            setResult(RESULT_OK, result);
            finish();
        });

        btnLoadMore.setOnClickListener(v -> loadMore());
        btnLoadAll.setOnClickListener(v -> loadAll());
        updateTitle();
    }

    private void restoreQueue() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("queue", "[]"));
            queue.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) queue.add(Lead.fromSavedJson(o));
            }
            currentIndex = prefs.getInt("queue_index", 0);
            if (currentIndex < 0 || currentIndex >= queue.size()) currentIndex = 0;
        } catch (Exception ignored) {
            queue.clear();
            currentIndex = 0;
        }
    }

    private void saveQueue() {
        try {
            JSONArray arr = new JSONArray();
            for (Lead l : queue) arr.put(l.toJson());
            prefs.edit().putString("queue", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Rebuild the shown list from the search query, then sort it if
     *  campaign sort is on, then scroll the list to the current lead
     *  the first time (no query active).
     *
     *  Filter first, then sort the filtered rows: the sort only
     *  reorders the queue indices in `shown`, so a tap still returns
     *  the lead's real queue index and the current-lead highlight
     *  (which compares queue indices) is unaffected. Campaign sort
     *  is a stable sort over the dialer-ordered list, so leads in
     *  the same campaign keep their dialer order. */
    private void applyFilter(boolean scrollToCurrent) {
        shown.clear();
        String q = query.toLowerCase(Locale.US);
        String qDigits = Lead.normalizePhone(query);
        for (int i = 0; i < queue.size(); i++) {
            Lead l = queue.get(i);
            if (q.isEmpty()) { shown.add(i); continue; }
            String biz = l.business == null ? "" : l.business.toLowerCase(Locale.US);
            String phone = l.phone == null ? "" : l.phone;
            boolean match = biz.contains(q) || phone.contains(query)
                    || (!qDigits.isEmpty() && Lead.normalizePhone(phone).contains(qDigits));
            if (match) shown.add(i);
        }
        if (SORT_CAMPAIGN.equals(sortMode)) {
            Collections.sort(shown, (a, b) -> {
                String ca = campaignOf(queue.get(a));
                String cb = campaignOf(queue.get(b));
                return ca.compareToIgnoreCase(cb);
            });
        }
        adapter.notifyDataSetChanged();
        updateTitle();
        if (scrollToCurrent && q.isEmpty()) {
            int pos = shown.indexOf(currentIndex);
            if (pos >= 0) lvQueue.setSelection(pos);
        }
    }

    private static String campaignOf(Lead l) {
        return l.campaign == null ? "" : l.campaign;
    }

    private void updateSortButton() {
        btnSort.setText(SORT_CAMPAIGN.equals(sortMode)
                ? "Sort: Campaign" : "Sort: Dialer order");
    }

    private void updateTitle() {
        String base = queue.isEmpty()
                ? "Queue is empty."
                : "Lead " + (currentIndex + 1) + " of " + queue.size()
                + "  ·  " + queue.size() + " lead" + (queue.size() == 1 ? "" : "s");
        tvTitle.setText(query.isEmpty() ? base
                : base + "  ·  showing " + shown.size() + " matching “" + query + "”");
    }

    // ---------- Load more ----------

    private void loadMore() {
        if (loadingMore || loadingAll || noMore) return;
        String base = prefs.getString("base_url", MainActivity.DEFAULT_BASE_URL);
        String key = prefs.getString("api_key", "");
        if (base == null || base.trim().isEmpty() || key == null || key.isEmpty()) {
            tvStatus.setText("Load more needs your CRM API key in Settings.");
            tvStatus.setTextColor(Color.parseColor("#B00020"));
            return;
        }
        String statusFilter = prefs.getString("status", MainActivity.DEFAULT_STATUS);
        String campaign = prefs.getString("campaign", MainActivity.DEFAULT_CAMPAIGN);
        // The CRM list is paged by offset over its own rows, ordered
        // by updated_at DESC — and recordCall bumps updated_at. A
        // lead whose saved disposition moved it OUT of this filter
        // (status left `new`, or its campaign changed) is no longer
        // in the CRM's filtered list at all, so counting it would
        // start the next page one row too late and skip the boundary
        // row forever. The offset therefore counts only queued CRM
        // leads that STILL match the active filter. CSV-only rows
        // appended at the end must not shift it either.
        int offset = 0;
        for (Lead l : queue) if (stillMatchesFilter(l, statusFilter, campaign)) offset++;
        loadingMore = true;
        btnLoadMore.setEnabled(false);
        btnLoadAll.setEnabled(false);
        tvStatus.setText("Loading more leads…");
        tvStatus.setTextColor(GRAY);
        CrmApi api = new CrmApi(base, key);
        final int off = offset;
        bg.execute(() -> {
            try {
                List<Lead> more = api.listLeads(statusFilter, campaign,
                        MainActivity.QUEUE_LIMIT, off);
                runOnUiThread(() -> {
                    loadingMore = false;
                    btnLoadMore.setEnabled(true);
                    btnLoadAll.setEnabled(!noMore);
                    // Dedupe against what is already queued (same CRM
                    // id, or same phone).
                    Set<Long> seenIds = new HashSet<>();
                    Set<String> seenPhones = new HashSet<>();
                    for (Lead l : queue) {
                        if (l.crmId != null) seenIds.add(l.crmId);
                        seenPhones.add(Lead.normalizePhone(l.phone));
                    }
                    int added = 0;
                    for (Lead l : more) {
                        if (l.crmId != null && seenIds.contains(l.crmId)) continue;
                        if (seenPhones.contains(Lead.normalizePhone(l.phone))) continue;
                        queue.add(l);
                        added++;
                    }
                    if (added == 0) {
                        noMore = true;
                        btnLoadMore.setEnabled(false);
                        btnLoadAll.setEnabled(false);
                        btnLoadMore.setText("No more leads");
                        tvStatus.setText(more.isEmpty()
                                ? "No more leads in the CRM for this filter."
                                : "Everything the CRM sent is already in the queue.");
                    } else {
                        saveQueue();
                        applyFilter(false);
                        tvStatus.setText("Added " + added + " more lead"
                                + (added == 1 ? "" : "s") + " — " + queue.size() + " in the queue now.");
                    }
                });
            } catch (CrmApi.ApiException e) {
                runOnUiThread(() -> {
                    loadingMore = false;
                    btnLoadMore.setEnabled(true);
                    btnLoadAll.setEnabled(true);
                    tvStatus.setText("Could not load more: " + e.getMessage());
                    tvStatus.setTextColor(Color.parseColor("#B00020"));
                });
            }
        });
    }

    // ---------- Load all ----------

    // Same paging as loadMore, but keeps going until the CRM
    // stops sending full pages — so the queue ends up holding
    // every lead for the active filter, not just the first 1,200.
    // The offset rule is the same as loadMore's: it counts only
    // queued CRM leads that STILL match the filter, so a lead
    // whose disposition moved it out never shifts a page boundary.
    // Hard cap of 100 pages (~10,000 leads) so a misbehaving
    // server can never loop forever.
    private void loadAll() {
        if (loadingMore || loadingAll || noMore) return;
        String base = prefs.getString("base_url", MainActivity.DEFAULT_BASE_URL);
        String key = prefs.getString("api_key", "");
        if (base == null || base.trim().isEmpty() || key == null || key.isEmpty()) {
            tvStatus.setText("Load all needs your CRM API key in Settings.");
            tvStatus.setTextColor(Color.parseColor("#B00020"));
            return;
        }
        String statusFilter = prefs.getString("status", MainActivity.DEFAULT_STATUS);
        String campaign = prefs.getString("campaign", MainActivity.DEFAULT_CAMPAIGN);
        loadingAll = true;
        btnLoadMore.setEnabled(false);
        btnLoadAll.setEnabled(false);
        tvStatus.setText("Loading all leads…");
        tvStatus.setTextColor(GRAY);
        CrmApi api = new CrmApi(base, key);
        bg.execute(() -> {
            try {
                Set<Long> seenIds = new HashSet<>();
                Set<String> seenPhones = new HashSet<>();
                for (Lead l : queue) {
                    if (l.crmId != null) seenIds.add(l.crmId);
                    seenPhones.add(Lead.normalizePhone(l.phone));
                }
                int addedTotal = 0;
                for (int page = 0; page < 100; page++) {
                    int offset = 0;
                    for (Lead l : queue) {
                        if (stillMatchesFilter(l, statusFilter, campaign)) offset++;
                    }
                    List<Lead> more = api.listLeads(statusFilter, campaign,
                            MainActivity.QUEUE_LIMIT, offset);
                    int added = 0;
                    for (Lead l : more) {
                        if (l.crmId != null && seenIds.contains(l.crmId)) continue;
                        if (seenPhones.contains(Lead.normalizePhone(l.phone))) continue;
                        if (l.crmId != null) seenIds.add(l.crmId);
                        seenPhones.add(Lead.normalizePhone(l.phone));
                        queue.add(l);
                        added++;
                    }
                    addedTotal += added;
                    final int total = queue.size();
                    runOnUiThread(() ->
                            tvStatus.setText("Loading all leads… " + total + " in the queue now."));
                    if (more.size() < MainActivity.QUEUE_LIMIT) break;
                    if (added == 0) break;
                }
                final int addedFinal = addedTotal;
                runOnUiThread(() -> {
                    loadingAll = false;
                    if (addedFinal == 0) {
                        noMore = true;
                        btnLoadMore.setEnabled(false);
                        btnLoadAll.setEnabled(false);
                        btnLoadMore.setText("No more leads");
                        tvStatus.setText("No more leads in the CRM for this filter.");
                    } else {
                        btnLoadMore.setEnabled(true);
                        btnLoadAll.setEnabled(true);
                        saveQueue();
                        applyFilter(false);
                        tvStatus.setText("Loaded all — " + queue.size() + " leads in the queue now.");
                    }
                });
            } catch (CrmApi.ApiException e) {
                runOnUiThread(() -> {
                    loadingAll = false;
                    btnLoadMore.setEnabled(true);
                    btnLoadAll.setEnabled(true);
                    tvStatus.setText("Could not load all: " + e.getMessage());
                    tvStatus.setTextColor(Color.parseColor("#B00020"));
                });
            }
        });
    }

    // ---------- filter matching (load-more offset) ----------

    // The saved disposition label for a lead (e.g. "Interested", or
    // "Shift campaign → restaurant") maps back to the outcome value
    // POSTed to recordCall (MainActivity OUTCOME_VALUES). "" means no
    // disposition, or a label this build does not recognise — both
    // are treated as "no status/campaign effect".
    private static String outcomeForDisposition(String disposition) {
        if (disposition == null) return "";
        String base = disposition;
        int arrow = base.indexOf('→');
        if (arrow >= 0) base = base.substring(0, arrow);
        switch (base.trim().toLowerCase(Locale.US)) {
            case "interested": return "interested";
            case "not interested": return "not_interested";
            case "no answer": return "no_answer";
            case "voicemail": return "voicemail";
            case "callback": return "callback";
            case "wrong number": return "wrong_number";
            case "do not contact": return "do_not_contact";
            case "not qualified": return "not_qualified";
            case "shift campaign": return "shift_campaign";
            default: return "";
        }
    }

    // Target campaign of a shift disposition ("Shift campaign →
    // restaurant" → "restaurant"), or "" if it is not a shift or
    // carries no target.
    private static String shiftTarget(String disposition) {
        if (disposition == null) return "";
        int arrow = disposition.indexOf('→');
        if (arrow < 0) return "";
        return disposition.substring(arrow + 1).trim();
    }

    /** The CRM status this lead is in now, given its saved
     *  disposition — the same effects crm.py CALL_STATUS_EFFECTS
     *  applies on recordCall: interested → taken_over,
     *  not_interested / wrong_number / do_not_contact →
     *  do_not_contact, not_qualified → not_qualified. No-answer,
     *  voicemail, callback and shift_campaign leave the status
     *  alone, so the lead is still in whatever status it was
     *  loaded with (`loadedStatus`). */
    private static String effectiveStatus(Lead l, String loadedStatus) {
        switch (outcomeForDisposition(l.disposition)) {
            case "interested": return "taken_over";
            case "not_interested":
            case "wrong_number":
            case "do_not_contact": return "do_not_contact";
            case "not_qualified": return "not_qualified";
            default: return loadedStatus;
        }
    }

    /** The campaign this lead is in now. A shift moves it to the
     *  picked target (status untouched); every other disposition
     *  leaves it where it was loaded. A shift with no readable
     *  target yields "" — it matches no non-empty campaign filter,
     *  so such a lead is conservatively left out of the offset
     *  (under-counting only re-fetches a row the dedupe then
     *  drops; over-counting would skip a row forever). */
    private static String effectiveCampaign(Lead l) {
        if ("shift_campaign".equals(outcomeForDisposition(l.disposition))) {
            return shiftTarget(l.disposition);
        }
        return campaignOf(l);
    }

    /** True when this queued lead is still one of the rows
     *  listLeads(statusFilter, campaignFilter) would return: a CRM
     *  lead (CSV-only rows never are) whose effective status and
     *  campaign still equal the active filter. An empty filter
     *  matches everything on that side, mirroring listLeads
     *  omitting the parameter. */
    private static boolean stillMatchesFilter(Lead l, String statusFilter, String campaignFilter) {
        if (l.crmId == null) return false;
        if (statusFilter != null && !statusFilter.isEmpty()
                && !statusFilter.equalsIgnoreCase(effectiveStatus(l, statusFilter))) return false;
        if (campaignFilter != null && !campaignFilter.isEmpty()
                && !campaignFilter.equalsIgnoreCase(effectiveCampaign(l))) return false;
        return true;
    }

    // ---------- list ----------

    private class QueueAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return queue.get(shown.get(position)); }
        @Override public long getItemId(int position) { return shown.get(position); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(QueueActivity.this)
                        .inflate(R.layout.item_queue_lead, parent, false);
            }
            int idx = shown.get(position);
            Lead l = queue.get(idx);
            TextView tvTitleRow = row.findViewById(R.id.tvRowTitle);
            TextView tvSub = row.findViewById(R.id.tvRowSub);
            String title = (idx + 1) + ". " + (l.business == null || l.business.isEmpty()
                    ? "(no business name)" : l.business);
            if (idx == currentIndex) title += "  ▶ NOW";
            tvTitleRow.setText(title);
            tvTitleRow.setTextColor(NAVY);
            String sub = l.phone == null ? "" : l.phone;
            if (l.campaign != null && !l.campaign.isEmpty()) {
                sub += "  ·  " + l.campaign;
            }
            if (l.disposition != null && !l.disposition.isEmpty()) {
                sub += "  ·  ✓ " + l.disposition;
            }
            tvSub.setText(sub);
            tvSub.setTextColor(l.disposition != null && !l.disposition.isEmpty()
                    ? DONE_GREEN : GRAY);
            row.setBackgroundColor(idx == currentIndex ? CURRENT_BG : Color.TRANSPARENT);
            return row;
        }
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }
}

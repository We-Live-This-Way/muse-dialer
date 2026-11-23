package com.welivethisway.dialer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CRM client over HttpURLConnection — no third-party libraries.
 * Only talks to the CRM base URL the operator enters in Settings, with the
 * Bearer key he pastes there. Nothing here holds a key or a URL.
 *
 * Endpoints (Lead Outreach CRM bot API):
 *   GET  /api/v1/listLeads?status=&campaign=&limit=
 *   GET  /api/v1/listCalls?lead_id=   (call history, newest first)
 *   GET  /api/v1/campaigns           (distinct campaign values in leads)
 *   POST /api/v1/recordCall  {"lead_id": <id>, "outcome": "<value>"}
 *   Shift campaign: same recordCall with outcome "shift_campaign"
 *   and "to_campaign": "<picked campaign>" — the CRM moves the
 *   lead to that campaign (its shift_campaign handling). The
 *   picker offers the campaigns from GET /api/v1/campaigns plus
 *   "restaurant" if the CRM does not list it, and a "New
 *   campaign…" row that lets the operator type a brand-new campaign
 *   name instead; the app checks the typed name against the
 *   CRM's naming rule before posting — one lowercase word,
 *   letters, numbers and hyphens, 1–30 characters, starting
 *   and ending with a letter or digit.
 *
 * NOTE: listCalls needs the CRM's crm.py to include that endpoint
 * (added with the dialer 1.4 notes read-back). Against an older CRM
 * it answers 404 — callers must treat that as "no history yet",
 * never as an error to show the operator.
 *
 * NOTE: recordCall is the contract this app is built to. If the CRM
 * answers it with anything but 200 the app keeps the lead on screen
 * with the error and a retry button — a disposition is never dropped.
 */
public class CrmApi {

    public static final String USER_AGENT = "WLTW-Dialer/1.0 (Android)";
    private static final int TIMEOUT_MS = 20000;

    /** Thrown for any non-200 answer or transport failure. The message
     *  is kept plain so it can be shown to the operator as-is. */
    public static class ApiException extends Exception {
        public final int httpStatus; // -1 = could not reach the server
        public ApiException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

    private final String baseUrl; // trailing slash stripped
    private final String apiKey;

    public CrmApi(String baseUrl, String apiKey) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public List<Lead> listLeads(String status, String campaign, int limit) throws ApiException {
        return listLeads(status, campaign, limit, 0);
    }

    /** Same list, starting at offset — the queue overview's "Load
     *  more" fetches the next page with offset = CRM leads already
     *  loaded, in the same order the CRM returns. */
    public List<Lead> listLeads(String status, String campaign, int limit, int offset) throws ApiException {
        try {
            StringBuilder qs = new StringBuilder();
            if (status != null && !status.isEmpty())
                qs.append("status=").append(URLEncoder.encode(status, "UTF-8"));
            if (campaign != null && !campaign.isEmpty()) {
                if (qs.length() > 0) qs.append('&');
                qs.append("campaign=").append(URLEncoder.encode(campaign, "UTF-8"));
            }
            if (qs.length() > 0) qs.append('&');
            qs.append("limit=").append(limit);
            if (offset > 0) qs.append("&offset=").append(offset);
            String body = get("/api/v1/listLeads?" + qs);
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("leads");
            if (arr == null) {
                // Tolerate a bare array response.
                arr = new JSONArray(body);
            }
            List<Lead> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Lead l = Lead.fromJson(o);
                if (l.phone == null || l.phone.trim().isEmpty()) continue;
                out.add(l);
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "Could not read the lead list: " + e.getMessage());
        }
    }

    /** One recorded call, as the CRM returns it from listCalls. */
    public static class CallRecord {
        public String outcome = "";
        public String note = "";
        public String calledAt = "";
        public String actor = "";
    }

    /** GET a lead's call history, newest first. Any failure (including
     *  404 from a CRM that predates listCalls) throws ApiException —
     *  the caller treats that as "no previous calls to show", never
     *  as an error to show the operator. */
    public List<CallRecord> listCalls(long leadId) throws ApiException {
        try {
            String body = get("/api/v1/listCalls?lead_id=" + leadId);
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("calls");
            List<CallRecord> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                CallRecord c = new CallRecord();
                c.outcome = o.optString("outcome", "");
                c.note = o.optString("note", "");
                c.calledAt = o.optString("called_at", "");
                c.actor = o.optString("actor", "");
                out.add(c);
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "Could not read the call history: " + e.getMessage());
        }
    }

    /** POST one call outcome. Returns only on HTTP 200; anything else
     *  throws, and the caller must keep the lead on screen. */
    public void recordCall(long leadId, String outcome) throws ApiException {
        recordCall(leadId, outcome, null, null);
    }

    /** Same, with an optional note for the call. An empty note is
     *  omitted from the payload — the CRM stores it in calls.note
     *  when present. */
    public void recordCall(long leadId, String outcome, String note) throws ApiException {
        recordCall(leadId, outcome, note, null);
    }

    /** Same, with an optional target campaign. Used by the
     *  "Shift campaign" disposition: outcome "shift_campaign"
     *  with toCampaign set to the campaign the operator picked in the
     *  picker — the CRM moves the lead to that campaign. A
     *  campaign typed in the picker's "New campaign…" row is
     *  validated in MainActivity against the CRM's naming rule
     *  (one lowercase word, letters/numbers/hyphens, 1–30 chars)
     *  before it reaches here. A null toCampaign is omitted from
     *  the payload, so every other disposition posts exactly as
     *  before. */
    public void recordCall(long leadId, String outcome, String note, String toCampaign) throws ApiException {
        try {
            JSONObject payload = new JSONObject();
            payload.put("lead_id", leadId);
            payload.put("outcome", outcome);
            if (note != null && !note.trim().isEmpty()) payload.put("note", note.trim());
            if (toCampaign != null && !toCampaign.trim().isEmpty())
                payload.put("to_campaign", toCampaign.trim());
            post("/api/v1/recordCall", payload.toString());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "Could not save the outcome: " + e.getMessage());
        }
    }

    /** GET the distinct campaign values present in the CRM's
     *  leads, e.g. {"campaigns": ["delivery"]}. Any failure
     *  (including 404 from a CRM that predates the endpoint)
     *  throws ApiException — the caller falls back to the fixed
     *  list ["delivery", "restaurant"] and never shows the operator an
     *  error for this. An empty list is returned as-is; the
     *  caller treats it as a failure too. */
    public List<String> listCampaigns() throws ApiException {
        try {
            String body = get("/api/v1/campaigns");
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("campaigns");
            List<String> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                String c = arr.optString(i, "").trim();
                if (!c.isEmpty()) out.add(c);
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "Could not read the campaign list: " + e.getMessage());
        }
    }

    private String get(String path) throws ApiException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            return readResult(c);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "Could not reach the CRM: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void post(String path, String jsonBody) throws ApiException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            readResult(c); // throws on non-200; body discarded
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "Could not reach the CRM: " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Returns the body on 200; on anything else throws ApiException
     *  with the status and a short, plain piece of the server's reply. */
    private String readResult(HttpURLConnection c) throws ApiException {
        try {
            int status = c.getResponseCode();
            // getResponseCode() can return -1 (no reply at all) instead
            // of throwing — treat that as unreachable, not as an
            // HTTP status, so the message stays plain and true.
            if (status == -1) {
                throw new ApiException(-1, "Could not reach the CRM: no reply from the server.");
            }
            InputStream in = status == 200 ? c.getInputStream() : c.getErrorStream();
            String body = "";
            if (in != null) {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                body = sb.toString().trim();
            }
            if (status == 200) return body;
            String detail = body;
            // Prefer a short JSON error message if the server sent one.
            try {
                JSONObject o = new JSONObject(body);
                if (o.has("error")) detail = o.optString("error", body);
                else if (o.has("message")) detail = o.optString("message", body);
            } catch (Exception ignored) {}
            if (detail.length() > 200) detail = detail.substring(0, 200) + "…";
            String msg;
            if (status == 401) msg = "CRM said the API key is wrong (401). Check the key in Settings.";
            else if (status == 404) msg = "CRM has no such address (404" + (detail.isEmpty() ? "" : ": " + detail) + ").";
            else msg = "CRM answered " + status + (detail.isEmpty() ? "." : ": " + detail);
            throw new ApiException(status, msg);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(-1, "Could not read the CRM reply: " + e.getMessage());
        }
    }
}

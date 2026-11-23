package com.welivethisway.dialer;

import org.json.JSONObject;

/**
 * One lead in the call queue.
 *
 * crmId is null for CSV rows that have no matching CRM lead — those are
 * local-only: their disposition is stored on the phone, never posted.
 */
public class Lead {
    public Long crmId;          // null = CSV-only lead, no CRM record
    public String business = "";
    public String phone = "";
    public String contact = ""; // contact name (CRM "name")
    public String notes = "";
    public String campaign = "";
    public String source = "crm"; // "crm" or "csv"
    /** Label of the outcome already saved for this lead this session
     *  (e.g. "Interested", "Not qualified"), or null if none yet.
     *  The queue overview shows it; jumping back to such a lead is
     *  allowed, and a new call + disposition posts a NEW record. */
    public String disposition = null;

    public Lead() {}

    /** Build from one object of the CRM listLeads response. */
    public static Lead fromJson(JSONObject o) {
        Lead l = new Lead();
        l.source = "crm";
        if (o.has("id") && !o.isNull("id")) l.crmId = o.optLong("id");
        l.business = o.optString("business", "");
        l.phone = o.optString("phone", "");
        l.contact = o.optString("name", "");
        l.notes = o.optString("notes", "");
        l.campaign = o.optString("campaign", "");
        return l;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            if (crmId != null) o.put("id", crmId.longValue());
            o.put("business", business);
            o.put("phone", phone);
            o.put("name", contact);
            o.put("notes", notes);
            o.put("campaign", campaign);
            o.put("source", source);
            if (disposition != null) o.put("disposition", disposition);
        } catch (Exception ignored) {}
        return o;
    }

    public static Lead fromSavedJson(JSONObject o) {
        Lead l = new Lead();
        if (o.has("id") && !o.isNull("id")) l.crmId = o.optLong("id");
        l.business = o.optString("business", "");
        l.phone = o.optString("phone", "");
        l.contact = o.optString("name", "");
        l.notes = o.optString("notes", "");
        l.campaign = o.optString("campaign", "");
        l.source = o.optString("source", "crm");
        l.disposition = o.has("disposition") && !o.isNull("disposition")
                ? o.optString("disposition", null) : null;
        return l;
    }

    /** Digits only, with a leading 1 stripped — so "(561) 406-0480",
     *  "5555550100" and "+1 555-555-0100" all match each other. */
    public static String normalizePhone(String raw) {
        if (raw == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        String d = digits.toString();
        if (d.length() == 11 && d.charAt(0) == '1') d = d.substring(1);
        return d;
    }

    /** Digits (and a leading +) for a tel: URI. */
    public String dialUri() {
        StringBuilder sb = new StringBuilder("tel:");
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if ((c >= '0' && c <= '9') || c == '+' || c == '*' || c == '#') sb.append(c);
        }
        return sb.toString();
    }
}

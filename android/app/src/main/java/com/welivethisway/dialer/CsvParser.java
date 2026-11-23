package com.welivethisway.dialer;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain CSV parsing — no libraries, no Android classes, so it can be
 * unit-tested on a plain JVM.
 *
 * Expected columns (header row, case-insensitive; extra columns ignored):
 *   business, phone, contact, notes
 * Header aliases accepted: business_name/company for business,
 * telephone/number for phone, name/contact_name for contact,
 * note for notes. If the first row has no recognisable header, the
 * columns are assumed to be in that order.
 * Quoted fields may contain commas, newlines and doubled quotes.
 * Rows without a phone number are skipped.
 */
public class CsvParser {

    public static List<Lead> parseLeads(String csvText) {
        List<Lead> out = new ArrayList<>();
        if (csvText == null) return out;
        List<String[]> rows = parseRows(csvText);
        if (rows.isEmpty()) return out;

        int businessCol = 0, phoneCol = 1, contactCol = 2, notesCol = 3;
        int start = 0;
        String[] first = rows.get(0);
        int headerPhone = findCol(first, "phone", "telephone", "number", "tel", "mobile");
        if (headerPhone >= 0) {
            businessCol = findCol(first, "business", "business_name", "company");
            phoneCol = headerPhone;
            contactCol = findCol(first, "contact", "name", "contact_name");
            notesCol = findCol(first, "notes", "note");
            start = 1;
        }
        for (int i = start; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String phone = col(r, phoneCol);
            if (phone.trim().isEmpty()) continue;
            Lead l = new Lead();
            l.source = "csv";
            l.crmId = null;
            l.business = col(r, businessCol);
            l.phone = phone.trim();
            l.contact = col(r, contactCol);
            l.notes = col(r, notesCol);
            out.add(l);
        }
        return out;
    }

    private static int findCol(String[] header, String... names) {
        for (int i = 0; i < header.length; i++) {
            String h = header[i].trim().toLowerCase();
            for (String n : names) if (h.equals(n)) return i;
        }
        return -1;
    }

    private static String col(String[] row, int idx) {
        if (idx < 0 || idx >= row.length || row[idx] == null) return "";
        return row[idx].trim();
    }

    /** Split CSV text into rows of fields. Handles quoted fields,
     *  "" escapes, commas and newlines inside quotes, CRLF. */
    public static List<String[]> parseRows(String text) {
        List<String[]> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && !fieldStarted && field.length() == 0) {
                inQuotes = true;
                fieldStarted = true;
            } else if (c == ',') {
                cur.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                cur.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
                if (!isBlankRow(cur)) rows.add(cur.toArray(new String[0]));
                cur = new ArrayList<>();
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        // Last row without a trailing newline.
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            if (!isBlankRow(cur)) rows.add(cur.toArray(new String[0]));
        }
        return rows;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String f : row) if (f != null && !f.trim().isEmpty()) return false;
        return true;
    }
}

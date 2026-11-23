package com.welivethisway.dialer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Settings: CRM address, API key, campaign and status filters.
 * The API key is typed in once by the operator and kept in the app's private
 * storage on the phone (SharedPreferences, MODE_PRIVATE). It is never
 * sent anywhere except to the CRM address entered here, and it is
 * never baked into the app. An About button here opens the About
 * screen (app version, CRM address, business contact).
 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private EditText etBaseUrl, etApiKey, etCampaign, etStatus;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etCampaign = findViewById(R.id.etCampaign);
        etStatus = findViewById(R.id.etStatus);
        tvStatus = findViewById(R.id.tvSettingsStatus);

        etBaseUrl.setText(prefs.getString("base_url", MainActivity.DEFAULT_BASE_URL));
        etApiKey.setText(prefs.getString("api_key", ""));
        etCampaign.setText(prefs.getString("campaign", MainActivity.DEFAULT_CAMPAIGN));
        etStatus.setText(prefs.getString("status", MainActivity.DEFAULT_STATUS));

        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAbout).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
    }

    private void saveSettings() {
        String base = etBaseUrl.getText().toString().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.isEmpty()) base = MainActivity.DEFAULT_BASE_URL;
        if (!base.startsWith("http://") && !base.startsWith("https://")) base = "https://" + base;
        prefs.edit()
                .putString("base_url", base)
                .putString("api_key", etApiKey.getText().toString().trim())
                .putString("campaign", etCampaign.getText().toString().trim())
                .putString("status", etStatus.getText().toString().trim())
                .apply();
        etBaseUrl.setText(base);
        tvStatus.setText("Settings saved. The API key stays on this phone only.");
    }
}

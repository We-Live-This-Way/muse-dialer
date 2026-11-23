package com.welivethisway.dialer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

/**
 * About: app name, the version this build actually is (read from
 * PackageManager at runtime, never hardcoded), a one-line
 * description, the CRM address the app is pointed at, and the
 * We Live This Way contact lines. Reached from Settings. No
 * network, no key — nothing is sent anywhere.
 */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        String versionName = "?";
        int versionCode = 0;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) versionName = info.versionName;
            versionCode = info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // A package always has its own info; "?" above is the fallback.
        }

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String baseUrl = prefs.getString("base_url", MainActivity.DEFAULT_BASE_URL);

        ((TextView) findViewById(R.id.tvAboutVersion))
                .setText("Version " + versionName + " (build " + versionCode + ")");
        ((TextView) findViewById(R.id.tvAboutCrmUrl)).setText(baseUrl);

        findViewById(R.id.btnAboutBack).setOnClickListener(v -> finish());
    }
}

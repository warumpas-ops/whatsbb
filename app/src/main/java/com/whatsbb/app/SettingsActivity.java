package com.whatsbb.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class SettingsActivity extends Activity {
    private PreferencesManager prefs;
    private EditText editServerUrl;
    private TextView tvTestResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new PreferencesManager(this);
        editServerUrl = (EditText) findViewById(R.id.edit_server_url);
        tvTestResult = (TextView) findViewById(R.id.tv_test_result);

        editServerUrl.setText(prefs.getServerUrl());

        findViewById(R.id.btn_settings_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        findViewById(R.id.btn_save_url).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = editServerUrl.getText().toString().trim();
                if (url.isEmpty()) { Toast.makeText(SettingsActivity.this, "URL cannot be empty", Toast.LENGTH_SHORT).show(); return; }
                prefs.setServerUrl(url);
                Toast.makeText(SettingsActivity.this, "Bridge URL saved", Toast.LENGTH_SHORT).show();
                testConnection(url);
            }
        });

        findViewById(R.id.btn_test_connection).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testConnection(editServerUrl.getText().toString().trim()); }
        });

        findViewById(R.id.btn_link_qr).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, QrActivity.class)); }
        });

        findViewById(R.id.btn_logout).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { new LogoutTask().execute(); }
        });
    }

    private void testConnection(String url) {
        tvTestResult.setVisibility(View.VISIBLE);
        tvTestResult.setTextColor(Color.YELLOW);
        tvTestResult.setText("Testing...");
        new TestTask(url).execute();
    }

    private class TestTask extends AsyncTask<Void, Void, JSONObject> {
        private final String url; private String error;
        TestTask(String url) { this.url = url; }

        @Override
        protected JSONObject doInBackground(Void... p) {
            try { return ApiClient.getStatus(url); }
            catch (Exception e) { error = e.getMessage(); return null; }
        }

        @Override
        protected void onPostExecute(JSONObject j) {
            if (j != null) {
                tvTestResult.setTextColor(Color.GREEN);
                tvTestResult.setText("\u2713 Bridge reached — Status: " + j.optString("status", "?"));
            } else {
                tvTestResult.setTextColor(Color.RED);
                tvTestResult.setText("\u2717 Failed: " + error);
            }
        }
    }

    private class LogoutTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Void... p) {
            try { ApiClient.logout(prefs.getServerUrl()); return true; }
            catch (Exception e) { return false; }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            if (ok) {
                Toast.makeText(SettingsActivity.this, "Logged out. Scan QR to re-link.", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(SettingsActivity.this, QrActivity.class));
            } else {
                Toast.makeText(SettingsActivity.this, "Logout failed — check bridge", Toast.LENGTH_SHORT).show();
            }
        }
    }
}

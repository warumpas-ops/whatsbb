package com.whatsbb.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class QrActivity extends Activity {
    private ImageView imgQr;
    private ProgressBar progressQr;
    private TextView tvQrStatus;
    private PreferencesManager prefs;

    private Handler pollHandler = new Handler();
    private Runnable pollRunnable;
    private boolean isPaused = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr);

        prefs = new PreferencesManager(this);
        imgQr = (ImageView) findViewById(R.id.img_qr);
        progressQr = (ProgressBar) findViewById(R.id.progress_qr);
        tvQrStatus = (TextView) findViewById(R.id.tv_qr_status);

        findViewById(R.id.btn_qr_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        findViewById(R.id.btn_qr_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fetchQrCode(); }
        });

        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPaused) {
                    fetchQrCode();
                    pollHandler.postDelayed(this, 3000);
                }
            }
        };

        fetchQrCode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isPaused = false;
        fetchQrCode();
        pollHandler.postDelayed(pollRunnable, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isPaused = true;
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void fetchQrCode() { new LoadQrTask().execute(); }

    private class LoadQrTask extends AsyncTask<Void, Void, QrResult> {
        @Override
        protected QrResult doInBackground(Void... p) {
            QrResult res = new QrResult();
            try {
                JSONObject json = ApiClient.getQR(prefs.getServerUrl());
                res.status = json.optString("status");
                String qrDataUrl = json.optString("qr", null);
                if (qrDataUrl != null && qrDataUrl.contains(",")) {
                    byte[] bytes = Base64.decode(qrDataUrl.substring(qrDataUrl.indexOf(",") + 1), Base64.DEFAULT);
                    res.bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
                res.success = true;
            } catch (Exception e) {
                res.success = false;
                res.error = e.getMessage();
            }
            return res;
        }

        @Override
        protected void onPostExecute(QrResult r) {
            progressQr.setVisibility(View.GONE);

            if (!r.success) {
                tvQrStatus.setText("Cannot reach bridge:\n" + r.error);
                return;
            }

            if ("CONNECTED".equals(r.status)) {
                tvQrStatus.setText("\u2713 WhatsApp Connected!");
                Toast.makeText(QrActivity.this, "Device linked successfully!", Toast.LENGTH_SHORT).show();
                pollHandler.postDelayed(new Runnable() {
                    @Override public void run() { finish(); }
                }, 1200);
                return;
            }

            if (r.bitmap != null) {
                imgQr.setImageBitmap(r.bitmap);
                tvQrStatus.setText("Point your phone camera at the screen");
            } else {
                tvQrStatus.setText("Generating QR code...");
            }
        }
    }

    private static class QrResult {
        boolean success;
        String status, error;
        Bitmap bitmap;
    }
}

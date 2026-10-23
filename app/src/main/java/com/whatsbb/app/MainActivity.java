package com.whatsbb.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private PreferencesManager prefs;
    private ListView listChats;
    private ChatAdapter adapter;
    private List<ChatModel> chatList = new ArrayList<ChatModel>();
    private ProgressBar progressLoading;
    private TextView tvEmpty;
    private View viewStatusDot;
    private LinearLayout layoutStatusBanner;
    private TextView tvStatusBanner;
    private TextView btnBannerAction;
    private View bannerDot;

    private Handler autoRefreshHandler = new Handler();
    private Runnable autoRefreshRunnable;
    private boolean isPaused = false;
    private String currentStatus = "DISCONNECTED";

    // Elegant monochrome avatar tones
    private static final int[] AVATAR_COLORS = {
        0xFF1E1E1E, 0xFF242424, 0xFF2A2A2A, 0xFF2F2F2F,
        0xFF343434, 0xFF222222, 0xFF2D2D2D, 0xFF1A1A1A
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new PreferencesManager(this);

        listChats = (ListView) findViewById(R.id.list_chats);
        progressLoading = (ProgressBar) findViewById(R.id.progress_loading);
        tvEmpty = (TextView) findViewById(R.id.tv_empty);
        viewStatusDot = findViewById(R.id.view_status_dot);
        layoutStatusBanner = (LinearLayout) findViewById(R.id.layout_status_banner);
        tvStatusBanner = (TextView) findViewById(R.id.tv_status_banner);
        btnBannerAction = (TextView) findViewById(R.id.btn_banner_action);
        bannerDot = findViewById(R.id.banner_dot);

        adapter = new ChatAdapter(this, chatList);
        listChats.setAdapter(adapter);

        listChats.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < chatList.size()) {
                    ChatModel chat = chatList.get(position);
                    openChat(chat.getId(), chat.getName());
                }
            }
        });

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { refreshData(true); }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        findViewById(R.id.btn_new_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showNewChatDialog(); }
        });

        btnBannerAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("SCAN_QR".equals(currentStatus)) {
                    startActivity(new Intent(MainActivity.this, QrActivity.class));
                } else {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
            }
        });

        autoRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPaused) {
                    refreshData(false);
                    autoRefreshHandler.postDelayed(this, 3500);
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        isPaused = false;
        refreshData(true);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, 3500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isPaused = true;
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
    }

    private void openChat(String jid, String name) {
        Intent intent = new Intent(MainActivity.this, ChatActivity.class);
        intent.putExtra("jid", jid);
        intent.putExtra("name", name);
        startActivity(intent);
    }

    private void showNewChatDialog() {
        new AsyncTask<Void, Void, List<ContactModel>>() {
            @Override
            protected List<ContactModel> doInBackground(Void... p) {
                try {
                    return ApiClient.getContacts(prefs.getServerUrl());
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(final List<ContactModel> contacts) {
                if (contacts == null || contacts.isEmpty()) {
                    showCustomNumberDialog();
                    return;
                }

                List<String> items = new ArrayList<String>();
                items.add("➕ Enter phone number...");
                for (ContactModel c : contacts) {
                    items.add(c.getName() + " (" + c.getJid().split("@")[0] + ")");
                }

                final CharSequence[] charItems = items.toArray(new CharSequence[items.size()]);
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("New Chat");
                builder.setItems(charItems, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showCustomNumberDialog();
                        } else {
                            ContactModel selected = contacts.get(which - 1);
                            openChat(selected.getJid(), selected.getName());
                        }
                    }
                });
                builder.setNegativeButton("Cancel", null);
                builder.show();
            }
        }.execute();
    }

    private void showCustomNumberDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Phone Number");
        builder.setMessage("Enter number with country code (e.g. 213775406221):");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("+213775406221");
        input.setTextColor(Color.WHITE);
        builder.setView(input);
        builder.setPositiveButton("Open", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String phone = input.getText().toString().trim().replaceAll("[^0-9]", "");
                if (phone.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Enter a valid phone number", Toast.LENGTH_SHORT).show();
                    return;
                }
                openChat(phone + "@s.whatsapp.net", "+" + phone);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void refreshData(boolean showProgress) {
        if (showProgress && chatList.isEmpty()) {
            progressLoading.setVisibility(View.VISIBLE);
        }
        new LoadChatsTask().execute();
    }

    private class LoadChatsTask extends AsyncTask<Void, Void, LoadResult> {
        @Override
        protected LoadResult doInBackground(Void... params) {
            LoadResult result = new LoadResult();
            try {
                JSONObject statusJson = ApiClient.getStatus(prefs.getServerUrl());
                result.status = statusJson.optString("status", "DISCONNECTED");
                result.chats = ApiClient.getChats(prefs.getServerUrl());
                result.success = true;
            } catch (Exception e) {
                result.success = false;
                result.error = e.getMessage();
                result.status = "DISCONNECTED";
            }
            return result;
        }

        @Override
        protected void onPostExecute(LoadResult result) {
            progressLoading.setVisibility(View.GONE);
            currentStatus = result.status;
            updateStatusUI(result.status, result.error);

            if (result.success && result.chats != null) {
                chatList.clear();
                chatList.addAll(result.chats);
                adapter.notifyDataSetChanged();
            }
            tvEmpty.setVisibility(chatList.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateStatusUI(String status, String error) {
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);

        GradientDrawable bannerDotBg = new GradientDrawable();
        bannerDotBg.setShape(GradientDrawable.OVAL);

        if ("CONNECTED".equals(status)) {
            dotBg.setColor(0xFF00A884);
            layoutStatusBanner.setVisibility(View.GONE);
        } else if ("SCAN_QR".equals(status)) {
            dotBg.setColor(0xFFF0B429);
            bannerDotBg.setColor(0xFFF0B429);
            bannerDot.setBackgroundDrawable(bannerDotBg);
            layoutStatusBanner.setVisibility(View.VISIBLE);
            tvStatusBanner.setText("Scan QR code to link WhatsApp");
            btnBannerAction.setText("PAIR");
        } else if ("CONNECTING".equals(status)) {
            dotBg.setColor(0xFF3B82F6);
            bannerDotBg.setColor(0xFF3B82F6);
            bannerDot.setBackgroundDrawable(bannerDotBg);
            layoutStatusBanner.setVisibility(View.VISIBLE);
            tvStatusBanner.setText("Connecting to bridge...");
            btnBannerAction.setText("SETTINGS");
        } else {
            dotBg.setColor(0xFFEF4444);
            bannerDotBg.setColor(0xFFEF4444);
            bannerDot.setBackgroundDrawable(bannerDotBg);
            layoutStatusBanner.setVisibility(View.VISIBLE);
            tvStatusBanner.setText(error != null ? "Bridge unreachable — " + error.split(":")[0] : "Not connected to bridge");
            btnBannerAction.setText("SETTINGS");
        }
        viewStatusDot.setBackgroundDrawable(dotBg);
    }

    private static class LoadResult {
        boolean success;
        String status;
        String error;
        List<ChatModel> chats;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_T:
                if (!chatList.isEmpty()) { listChats.setSelection(0); return true; }
                break;
            case KeyEvent.KEYCODE_B:
                if (!chatList.isEmpty()) { listChats.setSelection(chatList.size() - 1); return true; }
                break;
            case KeyEvent.KEYCODE_R:
                refreshData(true);
                return true;
            case KeyEvent.KEYCODE_S:
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                return true;
            case KeyEvent.KEYCODE_N:
                showNewChatDialog();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private class ChatAdapter extends BaseAdapter {
        private final Context context;
        private final List<ChatModel> items;
        private final LayoutInflater inflater;
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd/MM", Locale.getDefault());

        ChatAdapter(Context context, List<ChatModel> items) {
            this.context = context;
            this.items = items;
            this.inflater = LayoutInflater.from(context);
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_chat, parent, false);
                h = new ViewHolder();
                h.layoutAvatar = (FrameLayout) convertView.findViewById(R.id.layout_avatar);
                h.tvAvatarLetter = (TextView) convertView.findViewById(R.id.tv_avatar_letter);
                h.tvName = (TextView) convertView.findViewById(R.id.tv_chat_name);
                h.tvTime = (TextView) convertView.findViewById(R.id.tv_chat_time);
                h.tvPreviewTicks = (TextView) convertView.findViewById(R.id.tv_preview_ticks);
                h.tvLastMsg = (TextView) convertView.findViewById(R.id.tv_chat_last_msg);
                h.tvBadge = (TextView) convertView.findViewById(R.id.tv_unread_badge);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }

            ChatModel chat = items.get(position);

            // Avatar
            String name = chat.getName();
            String letter = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase(Locale.getDefault()) : "?";
            h.tvAvatarLetter.setText(letter);
            int colorIdx = Math.abs(chat.getId().hashCode()) % AVATAR_COLORS.length;
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(AVATAR_COLORS[colorIdx]);
            bg.setStroke(1, 0xFF383838);
            h.layoutAvatar.setBackgroundDrawable(bg);

            // Name
            h.tvName.setText(name);

            // Last message
            String preview = chat.getLastMessage();
            h.tvLastMsg.setText(preview != null ? preview : "");
            if (h.tvPreviewTicks != null) {
                h.tvPreviewTicks.setVisibility(View.GONE);
            }

            // Time
            if (chat.getTimestamp() > 0) {
                Date d = new Date(chat.getTimestamp());
                long ageHours = (System.currentTimeMillis() - chat.getTimestamp()) / 3600000L;
                h.tvTime.setText(ageHours < 24 ? timeFmt.format(d) : dateFmt.format(d));
            } else {
                h.tvTime.setText("");
            }

            // Unread badge
            if (chat.getUnread() > 0) {
                h.tvBadge.setVisibility(View.VISIBLE);
                h.tvBadge.setText(chat.getUnread() > 99 ? "99+" : String.valueOf(chat.getUnread()));
                // Green time for unread chats
                h.tvTime.setTextColor(0xFF00A884);
            } else {
                h.tvBadge.setVisibility(View.GONE);
                h.tvTime.setTextColor(0xFF667781);
            }

            return convertView;
        }

        private class ViewHolder {
            FrameLayout layoutAvatar;
            TextView tvAvatarLetter, tvName, tvTime, tvPreviewTicks, tvLastMsg, tvBadge;
        }
    }
}

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
import android.widget.Button;
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
    private Button btnBannerAction;

    private Handler autoRefreshHandler = new Handler();
    private Runnable autoRefreshRunnable;
    private boolean isPaused = false;
    private String currentStatus = "DISCONNECTED";

    private static final int[] AVATAR_COLORS = {
        0xFF1E88E5, 0xFF43A047, 0xFFE53935, 0xFFFB8C00,
        0xFF8E24AA, 0xFF00ACC1, 0xFF3949AB, 0xFFD81B60
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
        btnBannerAction = (Button) findViewById(R.id.btn_banner_action);

        adapter = new ChatAdapter(this, chatList);
        listChats.setAdapter(adapter);

        listChats.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ChatModel chat = chatList.get(position);
                openChat(chat.getId(), chat.getName());
            }
        });

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshData(true);
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        findViewById(R.id.btn_new_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNewChatDialog();
            }
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("New Conversation");
        builder.setMessage("Enter phone number with country code (e.g. +14155552671):");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("+1234567890");
        input.setTextColor(Color.WHITE);
        builder.setView(input);

        builder.setPositiveButton("Open Chat", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String phone = input.getText().toString().trim().replaceAll("[^0-9]", "");
                if (phone.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show();
                    return;
                }
                String jid = phone + "@s.whatsapp.net";
                openChat(jid, "+" + phone);
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
            String url = prefs.getServerUrl();
            LoadResult result = new LoadResult();
            try {
                JSONObject statusJson = ApiClient.getStatus(url);
                result.status = statusJson.optString("status", "DISCONNECTED");
                result.chats = ApiClient.getChats(url);
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

            if (chatList.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
            } else {
                tvEmpty.setVisibility(View.GONE);
            }
        }
    }

    private void updateStatusUI(String status, String error) {
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);

        if ("CONNECTED".equals(status)) {
            dotBg.setColor(0xFF25D366); // Green
            layoutStatusBanner.setVisibility(View.GONE);
        } else if ("SCAN_QR".equals(status)) {
            dotBg.setColor(0xFFFFAA00); // Amber
            layoutStatusBanner.setVisibility(View.VISIBLE);
            tvStatusBanner.setText("WhatsApp needs pairing. Tap to scan QR.");
            btnBannerAction.setText("Scan QR");
        } else if ("CONNECTING".equals(status)) {
            dotBg.setColor(0xFF3B82F6); // Blue
            layoutStatusBanner.setVisibility(View.VISIBLE);
            tvStatusBanner.setText("Connecting to WhatsApp bridge...");
            btnBannerAction.setText("Settings");
        } else {
            dotBg.setColor(0xFFEF4444); // Red
            layoutStatusBanner.setVisibility(View.VISIBLE);
            tvStatusBanner.setText(error != null ? "Bridge error: " + error : "Disconnected from bridge.");
            btnBannerAction.setText("Settings");
        }
        viewStatusDot.setBackgroundDrawable(dotBg);
    }

    private static class LoadResult {
        boolean success;
        String status;
        String error;
        List<ChatModel> chats;
    }

    // Physical BlackBerry Keyboard Navigation
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_T: // Jump to top
                if (chatList.size() > 0) {
                    listChats.setSelection(0);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_B: // Jump to bottom
                if (chatList.size() > 0) {
                    listChats.setSelection(chatList.size() - 1);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_R: // Refresh
                refreshData(true);
                Toast.makeText(this, "Refreshed", Toast.LENGTH_SHORT).show();
                return true;
            case KeyEvent.KEYCODE_S: // Settings
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                return true;
            case KeyEvent.KEYCODE_N: // New Chat
                showNewChatDialog();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private static class ChatAdapter extends BaseAdapter {
        private final Context context;
        private final List<ChatModel> items;
        private final LayoutInflater inflater;
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d", Locale.getDefault());

        public ChatAdapter(Context context, List<ChatModel> items) {
            this.context = context;
            this.items = items;
            this.inflater = LayoutInflater.from(context);
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_chat, parent, false);
                holder = new ViewHolder();
                holder.layoutAvatar = (FrameLayout) convertView.findViewById(R.id.layout_avatar);
                holder.tvAvatarLetter = (TextView) convertView.findViewById(R.id.tv_avatar_letter);
                holder.tvChatName = (TextView) convertView.findViewById(R.id.tv_chat_name);
                holder.tvChatTime = (TextView) convertView.findViewById(R.id.tv_chat_time);
                holder.tvChatLastMsg = (TextView) convertView.findViewById(R.id.tv_chat_last_msg);
                holder.tvUnreadBadge = (TextView) convertView.findViewById(R.id.tv_unread_badge);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            ChatModel chat = items.get(position);

            // Name & Avatar
            String displayName = chat.getName();
            holder.tvChatName.setText(displayName);

            String firstLetter = "?";
            if (displayName != null && !displayName.isEmpty()) {
                firstLetter = displayName.substring(0, 1).toUpperCase(Locale.getDefault());
            }
            holder.tvAvatarLetter.setText(firstLetter);

            int colorIndex = Math.abs(chat.getId().hashCode()) % AVATAR_COLORS.length;
            GradientDrawable avatarBg = new GradientDrawable();
            avatarBg.setShape(GradientDrawable.OVAL);
            avatarBg.setColor(AVATAR_COLORS[colorIndex]);
            holder.layoutAvatar.setBackgroundDrawable(avatarBg);

            // Last message
            holder.tvChatLastMsg.setText(chat.getLastMessage());

            // Timestamp
            if (chat.getTimestamp() > 0) {
                Date msgDate = new Date(chat.getTimestamp());
                long diffHours = (System.currentTimeMillis() - chat.getTimestamp()) / (1000 * 60 * 60);
                if (diffHours < 24) {
                    holder.tvChatTime.setText(timeFormat.format(msgDate));
                } else {
                    holder.tvChatTime.setText(dateFormat.format(msgDate));
                }
            } else {
                holder.tvChatTime.setText("");
            }

            // Unread badge
            if (chat.getUnread() > 0) {
                holder.tvUnreadBadge.setVisibility(View.VISIBLE);
                holder.tvUnreadBadge.setText(String.valueOf(chat.getUnread()));
            } else {
                holder.tvUnreadBadge.setVisibility(View.GONE);
            }

            return convertView;
        }

        private static class ViewHolder {
            FrameLayout layoutAvatar;
            TextView tvAvatarLetter;
            TextView tvChatName;
            TextView tvChatTime;
            TextView tvChatLastMsg;
            TextView tvUnreadBadge;
        }
    }
}

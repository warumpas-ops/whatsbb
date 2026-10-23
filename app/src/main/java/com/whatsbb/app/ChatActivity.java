package com.whatsbb.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ChatActivity extends Activity {
    private String jid;
    private String chatName;
    private PreferencesManager prefs;

    private ListView listMessages;
    private TextView tvChatEmpty;
    private EditText editMessage;
    private TextView btnSend;
    private TextView tvThreadName;
    private TextView tvThreadStatus;

    private MessageAdapter adapter;
    private List<MessageModel> messageList = new ArrayList<MessageModel>();

    private Handler pollHandler = new Handler();
    private Runnable pollRunnable;
    private boolean isPaused = false;
    private boolean isSending = false;
    private int lastMessageCount = 0;

    private static final int[] AVATAR_COLORS = {
        0xFF1A2744, 0xFF1A2E1A, 0xFF2E1A1A, 0xFF1A2833,
        0xFF2C1A2E, 0xFF2E2614, 0xFF0F2E2C, 0xFF2B1C0F
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        prefs = new PreferencesManager(this);
        jid = getIntent().getStringExtra("jid");
        chatName = getIntent().getStringExtra("name");
        if (chatName == null || chatName.isEmpty()) {
            chatName = jid != null ? "+" + jid.split("@")[0] : "Chat";
        }

        tvThreadName = (TextView) findViewById(R.id.tv_thread_name);
        tvThreadStatus = (TextView) findViewById(R.id.tv_thread_status);
        listMessages = (ListView) findViewById(R.id.list_messages);
        tvChatEmpty = (TextView) findViewById(R.id.tv_chat_empty);
        editMessage = (EditText) findViewById(R.id.edit_message);
        btnSend = (TextView) findViewById(R.id.btn_send);

        tvThreadName.setText(chatName);
        if (jid != null && jid.endsWith("@g.us")) {
            tvThreadStatus.setText("group");
        }

        // Set header avatar
        FrameLayout avatarFrame = (FrameLayout) findViewById(R.id.layout_chat_avatar);
        final ImageView ivAvatar = (ImageView) findViewById(R.id.iv_chat_avatar);
        final TextView avatarLetter = (TextView) findViewById(R.id.tv_chat_avatar_letter);

        if (chatName != null && !chatName.isEmpty()) {
            avatarLetter.setText(chatName.substring(0, 1).toUpperCase(Locale.getDefault()));
        }
        if (jid != null) {
            int colorIdx = Math.abs(jid.hashCode()) % AVATAR_COLORS.length;
            GradientDrawable avatarBg = new GradientDrawable();
            avatarBg.setShape(GradientDrawable.OVAL);
            avatarBg.setColor(AVATAR_COLORS[colorIdx]);
            avatarBg.setStroke(1, ThemeManager.DIVIDER);
            avatarFrame.setBackgroundDrawable(avatarBg);
        }

        // Load profile picture for this chat
        loadHeaderPfp(ivAvatar, avatarLetter);

        adapter = new MessageAdapter(this, messageList);
        listMessages.setAdapter(adapter);

        // Back
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        // Refresh
        findViewById(R.id.btn_chat_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { loadMessages(true); }
        });

        // Send button
        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { sendMessage(); }
        });

        // IME send action
        editMessage.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage();
                    return true;
                }
                return false;
            }
        });

        // Physical BB keyboard — Enter sends (Shift+Enter = newline)
        editMessage.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && !event.isShiftPressed()) {
                    sendMessage();
                    return true;
                }
                return false;
            }
        });

        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPaused) {
                    loadMessages(false);
                    pollHandler.postDelayed(this, 2500);
                }
            }
        };

        loadMessages(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isPaused = false;
        loadMessages(false);
        pollHandler.postDelayed(pollRunnable, 2500);
        editMessage.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isPaused = true;
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void loadHeaderPfp(final ImageView ivAvatar, final TextView avatarLetter) {
        if (jid == null) return;
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... p) {
                try {
                    String encodedJid = URLEncoder.encode(jid, "UTF-8");
                    URL url = new URL(prefs.getServerUrl() + "/api/profile-picture?jid=" + encodedJid);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(6000);
                    conn.setInstanceFollowRedirects(true);
                    if (conn.getResponseCode() == 200) {
                        InputStream is = conn.getInputStream();
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        is.close();
                        conn.disconnect();
                        return bmp;
                    }
                    conn.disconnect();
                } catch (Exception ignored) {}
                return null;
            }

            @Override
            protected void onPostExecute(Bitmap bmp) {
                if (bmp != null) {
                    ivAvatar.setImageBitmap(toCircle(bmp));
                    ivAvatar.setVisibility(View.VISIBLE);
                    avatarLetter.setVisibility(View.GONE);
                }
            }
        }.execute();
    }

    private Bitmap toCircle(Bitmap src) {
        int sz = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        canvas.drawCircle(sz / 2f, sz / 2f, sz / 2f, paint);
        return out;
    }

    private void sendMessage() {
        if (jid == null) return;
        String text = editMessage.getText().toString().trim();
        if (text.isEmpty() || isSending) return;

        editMessage.setText("");

        MessageModel opt = new MessageModel(
            "tmp_" + System.currentTimeMillis(),
            true, text, System.currentTimeMillis(), "You"
        );
        messageList.add(opt);
        adapter.notifyDataSetChanged();
        scrollToBottom();

        new SendTask(text).execute();
    }

    private void loadMessages(boolean scrollToBottom) {
        if (jid == null) return;
        new LoadMessagesTask(scrollToBottom).execute();
    }

    private void scrollToBottom() {
        if (!messageList.isEmpty()) {
            listMessages.post(new Runnable() {
                @Override
                public void run() {
                    listMessages.setSelection(adapter.getCount() - 1);
                }
            });
        }
    }

    private class LoadMessagesTask extends AsyncTask<Void, Void, List<MessageModel>> {
        private final boolean shouldScrollToBottom;
        LoadMessagesTask(boolean scrollToBottom) { this.shouldScrollToBottom = scrollToBottom; }

        @Override
        protected List<MessageModel> doInBackground(Void... p) {
            try { return ApiClient.getMessages(prefs.getServerUrl(), jid); }
            catch (Exception e) { return null; }
        }

        @Override
        protected void onPostExecute(List<MessageModel> result) {
            if (result != null) {
                boolean hadNewMessages = result.size() > lastMessageCount;
                messageList.clear();
                messageList.addAll(result);
                adapter.notifyDataSetChanged();

                if (messageList.isEmpty()) {
                    tvChatEmpty.setVisibility(View.VISIBLE);
                    listMessages.setVisibility(View.INVISIBLE);
                } else {
                    tvChatEmpty.setVisibility(View.GONE);
                    listMessages.setVisibility(View.VISIBLE);
                    if (shouldScrollToBottom || hadNewMessages) {
                        scrollToBottom();
                    }
                }
                lastMessageCount = result.size();
            }
        }
    }

    private class SendTask extends AsyncTask<Void, Void, Boolean> {
        private final String text;
        SendTask(String t) { text = t; }

        @Override protected void onPreExecute() { isSending = true; }

        @Override
        protected Boolean doInBackground(Void... p) {
            try {
                ApiClient.sendMessage(prefs.getServerUrl(), jid, text);
                return true;
            } catch (Exception e) { return false; }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            isSending = false;
            if (!ok) {
                Toast.makeText(ChatActivity.this, "Failed to send — check bridge", Toast.LENGTH_SHORT).show();
            }
            loadMessages(true);
        }
    }

    // ── Message list adapter ────────────────────────────────────────────────────
    private static class MessageAdapter extends BaseAdapter {
        private final Context ctx;
        private final List<MessageModel> items;
        private final LayoutInflater inflater;
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        MessageAdapter(Context ctx, List<MessageModel> items) {
            this.ctx = ctx;
            this.items = items;
            this.inflater = LayoutInflater.from(ctx);
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) {
            return items.get(position).isFromMe() ? 1 : 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            MessageModel msg = items.get(position);
            String timeStr = msg.getTimestamp() > 0
                ? timeFmt.format(new Date(msg.getTimestamp())) : "";

            if (msg.isFromMe()) {
                ViewHolderOut h;
                if (convertView == null) {
                    convertView = inflater.inflate(R.layout.item_message_out, parent, false);
                    h = new ViewHolderOut();
                    h.tvText = (TextView) convertView.findViewById(R.id.tv_text_out);
                    h.tvTime = (TextView) convertView.findViewById(R.id.tv_time_out);
                    h.tvTicks = (TextView) convertView.findViewById(R.id.tv_status_ticks);
                    convertView.setTag(h);
                } else {
                    h = (ViewHolderOut) convertView.getTag();
                }
                h.tvText.setText(msg.getText());
                h.tvTime.setText(timeStr);
                h.tvTicks.setText("\u2713\u2713");
            } else {
                ViewHolderIn h;
                if (convertView == null) {
                    convertView = inflater.inflate(R.layout.item_message_in, parent, false);
                    h = new ViewHolderIn();
                    h.tvSender = (TextView) convertView.findViewById(R.id.tv_sender_in);
                    h.tvText = (TextView) convertView.findViewById(R.id.tv_text_in);
                    h.tvTime = (TextView) convertView.findViewById(R.id.tv_time_in);
                    convertView.setTag(h);
                } else {
                    h = (ViewHolderIn) convertView.getTag();
                }
                boolean showSender = msg.getSender() != null
                    && !msg.getSender().isEmpty()
                    && !msg.getSender().equals("You");
                if (showSender) {
                    h.tvSender.setVisibility(View.VISIBLE);
                    h.tvSender.setText(msg.getSender());
                } else {
                    h.tvSender.setVisibility(View.GONE);
                }
                h.tvText.setText(msg.getText());
                h.tvTime.setText(timeStr);
            }
            return convertView;
        }

        private static class ViewHolderOut {
            TextView tvText, tvTime, tvTicks;
        }
        private static class ViewHolderIn {
            TextView tvSender, tvText, tvTime;
        }
    }
}

package com.whatsbb.app;

import android.app.Activity;
import android.content.Context;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

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
    private EditText editMessage;
    private FrameLayout btnSend;
    private TextView tvThreadName, tvThreadStatus;

    private MessageAdapter adapter;
    private List<MessageModel> messageList = new ArrayList<MessageModel>();

    private Handler pollHandler = new Handler();
    private Runnable pollRunnable;
    private boolean isPaused = false;
    private boolean isSending = false;

    private static final int[] AVATAR_COLORS = {
        0xFFE53935, 0xFF8E24AA, 0xFF1E88E5, 0xFF00897B,
        0xFF43A047, 0xFFFB8C00, 0xFF6D4C41, 0xFF3949AB
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
        editMessage = (EditText) findViewById(R.id.edit_message);
        btnSend = (FrameLayout) findViewById(R.id.btn_send);

        tvThreadName.setText(chatName);
        tvThreadStatus.setText(jid != null && jid.endsWith("@g.us") ? "Group" : "");

        // Set avatar in header
        FrameLayout avatarFrame = (FrameLayout) findViewById(R.id.layout_chat_avatar);
        TextView avatarLetter = (TextView) findViewById(R.id.tv_chat_avatar_letter);
        if (chatName != null && !chatName.isEmpty()) {
            avatarLetter.setText(chatName.substring(0, 1).toUpperCase(Locale.getDefault()));
        }
        int colorIdx = jid != null ? Math.abs(jid.hashCode()) % AVATAR_COLORS.length : 0;
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(AVATAR_COLORS[colorIdx]);
        avatarFrame.setBackgroundDrawable(avatarBg);

        adapter = new MessageAdapter(this, messageList);
        listMessages.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        findViewById(R.id.btn_chat_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fetchMessages(); }
        });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submitMessage(); }
        });

        editMessage.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { submitMessage(); return true; }
                return false;
            }
        });

        editMessage.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (!event.isShiftPressed()) { submitMessage(); return true; }
                }
                return false;
            }
        });

        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPaused) {
                    fetchMessages();
                    pollHandler.postDelayed(this, 2500);
                }
            }
        };

        fetchMessages();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isPaused = false;
        fetchMessages();
        pollHandler.postDelayed(pollRunnable, 2500);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isPaused = true;
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void submitMessage() {
        String text = editMessage.getText().toString().trim();
        if (text.isEmpty() || isSending) return;

        editMessage.setText("");

        // Optimistic message
        MessageModel opt = new MessageModel("tmp_" + System.currentTimeMillis(), true, text, System.currentTimeMillis(), "You");
        messageList.add(opt);
        adapter.notifyDataSetChanged();
        listMessages.setSelection(adapter.getCount() - 1);

        new SendTask(text).execute();
    }

    private void fetchMessages() {
        if (jid == null) return;
        new LoadMessagesTask().execute();
    }

    private class LoadMessagesTask extends AsyncTask<Void, Void, List<MessageModel>> {
        @Override
        protected List<MessageModel> doInBackground(Void... p) {
            try { return ApiClient.getMessages(prefs.getServerUrl(), jid); }
            catch (Exception e) { return null; }
        }

        @Override
        protected void onPostExecute(List<MessageModel> result) {
            if (result != null) {
                messageList.clear();
                messageList.addAll(result);
                adapter.notifyDataSetChanged();
                listMessages.setSelection(adapter.getCount() - 1);
            }
        }
    }

    private class SendTask extends AsyncTask<Void, Void, Boolean> {
        private final String text;
        SendTask(String text) { this.text = text; }

        @Override protected void onPreExecute() { isSending = true; }

        @Override
        protected Boolean doInBackground(Void... p) {
            try { ApiClient.sendMessage(prefs.getServerUrl(), jid, text); return true; }
            catch (Exception e) { return false; }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            isSending = false;
            if (!ok) Toast.makeText(ChatActivity.this, "Failed to send — check bridge", Toast.LENGTH_SHORT).show();
            fetchMessages();
        }
    }

    private static class MessageAdapter extends BaseAdapter {
        private final Context context;
        private final List<MessageModel> items;
        private final LayoutInflater inflater;
        private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

        MessageAdapter(Context ctx, List<MessageModel> items) {
            context = ctx;
            this.items = items;
            inflater = LayoutInflater.from(ctx);
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_message, parent, false);
                h = new ViewHolder();
                h.layoutIn = (LinearLayout) convertView.findViewById(R.id.layout_bubble_in);
                h.tvSenderIn = (TextView) convertView.findViewById(R.id.tv_sender_in);
                h.tvTextIn = (TextView) convertView.findViewById(R.id.tv_text_in);
                h.tvTimeIn = (TextView) convertView.findViewById(R.id.tv_time_in);
                h.layoutOut = (LinearLayout) convertView.findViewById(R.id.layout_bubble_out);
                h.tvTextOut = (TextView) convertView.findViewById(R.id.tv_text_out);
                h.tvTimeOut = (TextView) convertView.findViewById(R.id.tv_time_out);
                h.tvTicks = (TextView) convertView.findViewById(R.id.tv_status_ticks);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }

            MessageModel msg = items.get(position);
            String time = msg.getTimestamp() > 0 ? timeFmt.format(new Date(msg.getTimestamp())) : "";

            if (msg.isFromMe()) {
                h.layoutIn.setVisibility(View.GONE);
                h.layoutOut.setVisibility(View.VISIBLE);
                h.tvTextOut.setText(msg.getText());
                h.tvTimeOut.setText(time);
                h.tvTicks.setText("\u2713\u2713");
            } else {
                h.layoutOut.setVisibility(View.GONE);
                h.layoutIn.setVisibility(View.VISIBLE);
                boolean showSender = msg.getSender() != null && !msg.getSender().isEmpty() && !msg.getSender().equals("You");
                h.tvSenderIn.setVisibility(showSender ? View.VISIBLE : View.GONE);
                if (showSender) h.tvSenderIn.setText(msg.getSender());
                h.tvTextIn.setText(msg.getText());
                h.tvTimeIn.setText(time);
            }
            return convertView;
        }

        private static class ViewHolder {
            LinearLayout layoutIn, layoutOut;
            TextView tvSenderIn, tvTextIn, tvTimeIn;
            TextView tvTextOut, tvTimeOut, tvTicks;
        }
    }
}

package com.whatsbb.app;

import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
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
    private Button btnSend;
    private TextView tvThreadName;
    private TextView tvThreadStatus;

    private MessageAdapter adapter;
    private List<MessageModel> messageList = new ArrayList<MessageModel>();

    private Handler pollHandler = new Handler();
    private Runnable pollRunnable;
    private boolean isPaused = false;
    private boolean isSending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        prefs = new PreferencesManager(this);

        jid = getIntent().getStringExtra("jid");
        chatName = getIntent().getStringExtra("name");
        if (chatName == null || chatName.isEmpty()) {
            chatName = jid != null ? jid.split("@")[0] : "Chat";
        }

        tvThreadName = (TextView) findViewById(R.id.tv_thread_name);
        tvThreadStatus = (TextView) findViewById(R.id.tv_thread_status);
        listMessages = (ListView) findViewById(R.id.list_messages);
        editMessage = (EditText) findViewById(R.id.edit_message);
        btnSend = (Button) findViewById(R.id.btn_send);

        tvThreadName.setText(chatName);
        tvThreadStatus.setText(jid != null && jid.endsWith("@g.us") ? "Group" : "Tap here for info");

        adapter = new MessageAdapter(this, messageList);
        listMessages.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btn_chat_refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchMessages();
            }
        });

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitMessage();
            }
        });

        // Hardware keyboard Enter / IME Send key
        editMessage.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitMessage();
                    return true;
                }
                return false;
            }
        });

        // Physical BB keyboard Enter key handling
        editMessage.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (!event.isShiftPressed() && !event.isAltPressed()) {
                        submitMessage();
                        return true;
                    }
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
        if (text.isEmpty() || isSending) {
            return;
        }

        // Clear input immediately for fast typing feel
        editMessage.setText("");

        // Optimistically add message to UI
        MessageModel optimisticMsg = new MessageModel(
            "temp_" + System.currentTimeMillis(),
            true,
            text,
            System.currentTimeMillis(),
            "You"
        );
        messageList.add(optimisticMsg);
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
        protected List<MessageModel> doInBackground(Void... params) {
            try {
                return ApiClient.getMessages(prefs.getServerUrl(), jid);
            } catch (Exception e) {
                return null;
            }
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

        public SendTask(String text) {
            this.text = text;
        }

        @Override
        protected void onPreExecute() {
            isSending = true;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                ApiClient.sendMessage(prefs.getServerUrl(), jid, text);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            isSending = false;
            if (!success) {
                Toast.makeText(ChatActivity.this, "Failed to deliver message. Check bridge.", Toast.LENGTH_SHORT).show();
            }
            fetchMessages();
        }
    }

    private static class MessageAdapter extends BaseAdapter {
        private final Context context;
        private final List<MessageModel> items;
        private final LayoutInflater inflater;
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        public MessageAdapter(Context context, List<MessageModel> items) {
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
                convertView = inflater.inflate(R.layout.item_message, parent, false);
                holder = new ViewHolder();
                holder.layoutBubbleIn = (LinearLayout) convertView.findViewById(R.id.layout_bubble_in);
                holder.tvSenderIn = (TextView) convertView.findViewById(R.id.tv_sender_in);
                holder.tvTextIn = (TextView) convertView.findViewById(R.id.tv_text_in);
                holder.tvTimeIn = (TextView) convertView.findViewById(R.id.tv_time_in);

                holder.layoutBubbleOut = (LinearLayout) convertView.findViewById(R.id.layout_bubble_out);
                holder.tvTextOut = (TextView) convertView.findViewById(R.id.tv_text_out);
                holder.tvTimeOut = (TextView) convertView.findViewById(R.id.tv_time_out);
                holder.tvStatusTicks = (TextView) convertView.findViewById(R.id.tv_status_ticks);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            MessageModel msg = items.get(position);
            String timeStr = msg.getTimestamp() > 0 ? timeFormat.format(new Date(msg.getTimestamp())) : "";

            if (msg.isFromMe()) {
                holder.layoutBubbleIn.setVisibility(View.GONE);
                holder.layoutBubbleOut.setVisibility(View.VISIBLE);

                holder.tvTextOut.setText(msg.getText());
                holder.tvTimeOut.setText(timeStr);
                holder.tvStatusTicks.setText("\u2713\u2713");
            } else {
                holder.layoutBubbleOut.setVisibility(View.GONE);
                holder.layoutBubbleIn.setVisibility(View.VISIBLE);

                if (msg.getSender() != null && !msg.getSender().isEmpty() && !msg.getSender().equals("You")) {
                    holder.tvSenderIn.setText(msg.getSender());
                    holder.tvSenderIn.setVisibility(View.VISIBLE);
                } else {
                    holder.tvSenderIn.setVisibility(View.GONE);
                }

                holder.tvTextIn.setText(msg.getText());
                holder.tvTimeIn.setText(timeStr);
            }

            return convertView;
        }

        private static class ViewHolder {
            LinearLayout layoutBubbleIn;
            TextView tvSenderIn;
            TextView tvTextIn;
            TextView tvTimeIn;

            LinearLayout layoutBubbleOut;
            TextView tvTextOut;
            TextView tvTimeOut;
            TextView tvStatusTicks;
        }
    }
}

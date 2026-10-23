package com.whatsbb.app;

public class ChatModel {
    private String id; // jid, e.g. 1234567890@s.whatsapp.net or xxx@g.us
    private String name;
    private String lastMessage;
    private long timestamp;
    private int unread;
    private boolean isGroup;

    public ChatModel(String id, String name, String lastMessage, long timestamp, int unread, boolean isGroup) {
        this.id = id;
        this.name = name != null && !name.isEmpty() ? name : id;
        this.lastMessage = lastMessage != null ? lastMessage : "";
        this.timestamp = timestamp;
        this.unread = unread;
        this.isGroup = isGroup;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getUnread() {
        return unread;
    }

    public void setUnread(int unread) {
        this.unread = unread;
    }

    public boolean isGroup() {
        return isGroup;
    }
}

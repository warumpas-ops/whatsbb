package com.whatsbb.app;

public class MessageModel {
    private String id;
    private boolean fromMe;
    private String text;
    private long timestamp;
    private String sender;

    public MessageModel(String id, boolean fromMe, String text, long timestamp, String sender) {
        this.id = id;
        this.fromMe = fromMe;
        this.text = text != null ? text : "";
        this.timestamp = timestamp;
        this.sender = sender != null ? sender : (fromMe ? "You" : "");
    }

    public String getId() {
        return id;
    }

    public boolean isFromMe() {
        return fromMe;
    }

    public String getText() {
        return text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getSender() {
        return sender;
    }
}

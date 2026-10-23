package com.whatsbb.app;

public class ContactModel {
    private String jid;
    private String name;

    public ContactModel(String jid, String name) {
        this.jid = jid;
        this.name = name;
    }

    public String getJid() { return jid; }
    public String getName() { return name; }

    @Override
    public String toString() {
        return (name != null && !name.isEmpty()) ? name : jid;
    }
}

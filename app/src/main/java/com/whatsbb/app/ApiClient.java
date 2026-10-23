package com.whatsbb.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class ApiClient {
    private static final int TIMEOUT_MS = 8000;

    private static String get(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "WhatsBB/1.0 (BlackBerry 10)");
        conn.setRequestProperty("Accept", "application/json");

        int responseCode = conn.getResponseCode();
        InputStream is = (responseCode >= 200 && responseCode < 400) ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        if (responseCode >= 400) {
            throw new Exception("HTTP " + responseCode + ": " + sb.toString());
        }
        return sb.toString();
    }

    private static String post(String urlString, String jsonBody) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "WhatsBB/1.0 (BlackBerry 10)");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        if (jsonBody != null) {
            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.flush();
            os.close();
        }

        int responseCode = conn.getResponseCode();
        InputStream is = (responseCode >= 200 && responseCode < 400) ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        if (responseCode >= 400) {
            throw new Exception("HTTP " + responseCode + ": " + sb.toString());
        }
        return sb.toString();
    }

    public static JSONObject getStatus(String baseUrl) throws Exception {
        String resp = get(baseUrl + "/api/status");
        return new JSONObject(resp);
    }

    public static JSONObject getQR(String baseUrl) throws Exception {
        String resp = get(baseUrl + "/api/qr");
        return new JSONObject(resp);
    }

    public static List<ContactModel> getContacts(String baseUrl) throws Exception {
        String resp = get(baseUrl + "/api/contacts");
        JSONObject json = new JSONObject(resp);
        JSONArray arr = json.optJSONArray("contacts");
        List<ContactModel> list = new ArrayList<ContactModel>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new ContactModel(obj.optString("jid"), obj.optString("name")));
            }
        }
        return list;
    }

    public static List<ChatModel> getChats(String baseUrl) throws Exception {
        String resp = get(baseUrl + "/api/chats");
        JSONObject json = new JSONObject(resp);
        JSONArray arr = json.optJSONArray("chats");
        List<ChatModel> list = new ArrayList<ChatModel>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id");
                String name = obj.optString("name", id);
                String lastMessage = obj.optString("lastMessage", "");
                long timestamp = obj.optLong("timestamp", 0);
                int unread = obj.optInt("unread", 0);
                boolean isGroup = obj.optBoolean("isGroup", false);
                list.add(new ChatModel(id, name, lastMessage, timestamp, unread, isGroup));
            }
        }
        return list;
    }

    public static List<MessageModel> getMessages(String baseUrl, String jid) throws Exception {
        String encodedJid = URLEncoder.encode(jid, "UTF-8");
        String resp = get(baseUrl + "/api/messages?jid=" + encodedJid);
        JSONObject json = new JSONObject(resp);
        JSONArray arr = json.optJSONArray("messages");
        List<MessageModel> list = new ArrayList<MessageModel>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id");
                boolean fromMe = obj.optBoolean("fromMe", false);
                String text = obj.optString("text", "");
                long timestamp = obj.optLong("timestamp", 0);
                String sender = obj.optString("sender", fromMe ? "You" : "");
                list.add(new MessageModel(id, fromMe, text, timestamp, sender));
            }
        }
        return list;
    }

    public static JSONObject sendMessage(String baseUrl, String jid, String text) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("jid", jid);
        payload.put("text", text);
        String resp = post(baseUrl + "/api/send", payload.toString());
        return new JSONObject(resp);
    }

    public static JSONObject logout(String baseUrl) throws Exception {
        String resp = post(baseUrl + "/api/logout", "{}");
        return new JSONObject(resp);
    }
}

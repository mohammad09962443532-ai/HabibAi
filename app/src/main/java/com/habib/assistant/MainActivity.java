package com.habib.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private EditText serverUrl, message;
    private TextView output;
    private TextToSpeech tts;
    private static final int SPEECH_REQUEST = 42;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        tts = new TextToSpeech(this, status -> {});
        buildUi();
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24, 28, 24, 24);

        TextView title = new TextView(this);
        title.setText("حبیب دستیار");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        page.addView(title);

        TextView hint = new TextView(this);
        hint.setText("آدرس سرور حبیب را وارد کن؛ نمونه: http://192.168.1.10:5000");
        page.addView(hint);

        serverUrl = new EditText(this);
        serverUrl.setSingleLine(true);
        serverUrl.setHint("نشانی سرور Habib AI");
        serverUrl.setText(getPreferences(0).getString("server", ""));
        page.addView(serverUrl);

        message = new EditText(this);
        message.setHint("پیام خود را بنویس...");
        page.addView(message);

        Button speak = new Button(this);
        speak.setText("گفتن با صدا");
        page.addView(speak);
        speak.setOnClickListener(v -> startSpeech());

        Button send = new Button(this);
        send.setText("ارسال به حبیب");
        page.addView(send);
        send.setOnClickListener(v -> sendMessage());

        output = new TextView(this);
        output.setText("وضعیت: آماده");
        output.setTextSize(16);
        output.setPadding(8, 18, 8, 8);
        page.addView(output);
        setContentView(page);
    }

    private void startSpeech() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "با حبیب صحبت کن");
        try {
            startActivityForResult(intent, SPEECH_REQUEST);
        } catch (ActivityNotFoundException e) {
            output.setText("سرویس تشخیص گفتار روی گوشی پیدا نشد.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SPEECH_REQUEST && result == RESULT_OK && data != null) {
            ArrayList<String> texts =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (texts != null && !texts.isEmpty()) message.setText(texts.get(0));
        }
    }

    private void sendMessage() {
        final String text = message.getText().toString().trim();
        final String base = serverUrl.getText().toString().trim();
        if (text.isEmpty()) {
            output.setText("اول پیام را بنویس یا با صدا بگو.");
            return;
        }
        if (base.isEmpty()) {
            output.setText("ابتدا آدرس سرور حبیب را وارد کن.");
            return;
        }
        getPreferences(0).edit().putString("server", base).apply();
        output.setText("در حال ارتباط با سرور...");

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String endpoint = base.endsWith("/") ? base + "chat" : base + "/chat";
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                JSONObject request = new JSONObject();
                request.put("message", text);
                try (OutputStream stream = connection.getOutputStream()) {
                    stream.write(request.toString().getBytes("UTF-8"));
                }

                int code = connection.getResponseCode();
                InputStream stream = (code >= 200 && code < 400)
                        ? connection.getInputStream() : connection.getErrorStream();
                StringBuilder raw = new StringBuilder();
                if (stream != null) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(stream, "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) raw.append(line);
                    reader.close();
                }

                String answer = raw.toString();
                try {
                    JSONObject json = new JSONObject(answer);
                    if (json.has("answer")) answer = json.optString("answer");
                    else if (json.has("response")) answer = json.optString("response");
                } catch (Exception ignored) { }

                final String display = "HTTP " + code + "\n" + answer;
                final String spoken = answer;
                runOnUiThread(() -> {
                    output.setText(display);
                    if (tts != null && !spoken.isEmpty())
                        tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "habib");
                });
            } catch (Exception e) {
                final String error = "خطا در اتصال: " + e.getMessage()
                        + "\nآدرس سرور و مسیر /chat را بررسی کن.";
                runOnUiThread(() -> output.setText(error));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}

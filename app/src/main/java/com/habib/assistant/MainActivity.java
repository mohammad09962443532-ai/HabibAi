
package com.habib.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.*;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private EditText serverUrl, message;
    private TextView output;
    private TextToSpeech tts;

    private static final int SPEECH_REQUEST = 42;

    @Override
    public void onCreate(Bundle state) {
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
        hint.setText("آدرس سرور حبیب را وارد کن");
        page.addView(hint);

        serverUrl = new EditText(this);
        serverUrl.setSingleLine(true);
        serverUrl.setHint("آدرس سرور");
        serverUrl.setText(
                getPreferences(0).getString("server", "http://127.0.0.1:5000")
        );
        page.addView(serverUrl);

        message = new EditText(this);
        message.setHint("پیام خود را بنویس یا با صدا بگو...");
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

        Intent intent = new Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "fa-IR"
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "با حبیب صحبت کن"
        );

        try {
            startActivityForResult(intent, SPEECH_REQUEST);
        } catch (ActivityNotFoundException e) {
            output.setText("سرویس تشخیص گفتار پیدا نشد.");
        }
    }

    @Override
    protected void onActivityResult(
            int request,
            int result,
            Intent data
    ) {
        super.onActivityResult(request, result, data);

        if (request == SPEECH_REQUEST
                && result == RESULT_OK
                && data != null) {

            ArrayList<String> texts =
                    data.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS
                    );

            if (texts != null && !texts.isEmpty()) {
                message.setText(texts.get(0));
            }
        }
    }

    private void sendMessage() {

        final String text =
                message.getText().toString().trim();

        final String base =
                serverUrl.getText().toString().trim();

        if (text.isEmpty()) {
            output.setText("اول پیام را بنویس یا با صدا بگو.");
            return;
        }

        // اول بررسی دستورهای باز کردن برنامه
        if (handleAppCommand(text)) {
            return;
        }

        // بررسی دستور پیام واتساپ
        if (handleWhatsAppMessage(text)) {
            return;
        }

        if (base.isEmpty()) {
            output.setText("ابتدا آدرس سرور را وارد کن.");
            return;
        }

        getPreferences(0)
                .edit()
                .putString("server", base)
                .apply();

        output.setText("در حال ارتباط با حبیب...");

        new Thread(() -> {

            HttpURLConnection connection = null;

            try {

                String endpoint = base.endsWith("/")
                        ? base + "chat"
                        : base + "/chat";

                connection = (HttpURLConnection)
                        new URL(endpoint).openConnection();

                connection.setRequestMethod("POST");

                connection.setConnectTimeout(20000);

                // افزایش زمان انتظار پاسخ
                connection.setReadTimeout(120000);

                connection.setDoOutput(true);

                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                );

                JSONObject request = new JSONObject();
                request.put("message", text);

                try (OutputStream stream =
                             connection.getOutputStream()) {

                    stream.write(
                            request.toString().getBytes("UTF-8")
                    );
                }

                int code = connection.getResponseCode();

                InputStream stream =
                        (code >= 200 && code < 400)
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                StringBuilder raw = new StringBuilder();

                if (stream != null) {

                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            stream,
                                            "UTF-8"
                                    )
                            );

                    String line;

                    while ((line = reader.readLine()) != null) {
                        raw.append(line);
                    }

                    reader.close();
                }

                String answer = raw.toString();

                try {

                    JSONObject json =
                            new JSONObject(answer);

                    if (json.has("answer")) {
                        answer = json.optString("answer");
                    } else if (json.has("response")) {
                        answer = json.optString("response");
                    }

                } catch (Exception ignored) {}

                final String display =
                        "HTTP " + code + "\n" + answer;

                final String spoken = answer;

                runOnUiThread(() -> {

                    output.setText(display);

                    if (tts != null && !spoken.isEmpty()) {
                        tts.speak(
                                spoken,
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                "habib"
                        );
                    }
                });

            } catch (Exception e) {

                final String error =
                        "خطا در اتصال: " + e.getMessage();

                runOnUiThread(() -> output.setText(error));

            } finally {

                if (connection != null) {
                    connection.disconnect();
                }
            }

        }).start();
    }

    // باز کردن برنامه‌ها با دستور فارسی یا انگلیسی
    private boolean handleAppCommand(String command) {

        String text = command.toLowerCase(Locale.ROOT);

        boolean isOpenCommand =
                text.contains("باز کن")
                || text.contains("بازش کن")
                || text.contains("open ")
                || text.startsWith("open");

        if (!isOpenCommand) {
            return false;
        }

        String packageName = null;
        String appName = "";

        if (text.contains("واتساپ")
                || text.contains("whatsapp")) {

            packageName = "com.whatsapp";
            appName = "واتساپ";

        } else if (text.contains("تلگرام")
                || text.contains("telegram")) {

            packageName = "org.telegram.messenger";
            appName = "تلگرام";

        } else if (text.contains("اینستاگرام")
                || text.contains("instagram")) {

            packageName = "com.instagram.android";
            appName = "اینستاگرام";

        } else if (text.contains("یوتیوب")
                || text.contains("youtube")) {

            packageName = "com.google.android.youtube";
            appName = "یوتیوب";

        } else if (text.contains("کروم")
                || text.contains("chrome")) {

            packageName = "com.android.chrome";
            appName = "کروم";
        }

        if (packageName == null) {
            return false;
        }

        try {

            Intent intent =
                    getPackageManager()
                            .getLaunchIntentForPackage(packageName);

            if (intent == null) {
                output.setText(
                        "برنامه " + appName + " نصب نیست یا قابل باز کردن نیست."
                );
                return true;
            }

            startActivity(intent);

            output.setText("در حال باز کردن " + appName);

        } catch (Exception e) {

            output.setText("خطا در باز کردن برنامه: " + e.getMessage());
        }

        return true;
    }

    // باز کردن گفت‌وگوی واتساپ با شماره و متن آماده
    private boolean handleWhatsAppMessage(String command) {

        String text = command.toLowerCase(Locale.ROOT);

        boolean isWhatsApp =
                text.contains("واتساپ")
                || text.contains("whatsapp");

        boolean isMessage =
                text.contains("پیام")
                || text.contains("message");

        if (!isWhatsApp || !isMessage) {
            return false;
        }

        // پیدا کردن شماره تلفن
        Matcher numberMatcher = Pattern.compile(
                "(\\+?\\d[\\d\\s()-]{7,18}\\d)"
        ).matcher(command);

        if (!numberMatcher.find()) {

            output.setText(
                    "شماره تلفن را با کد کشور بگو. مثال: 491234567890"
            );

            return true;
        }

        String phone = numberMatcher.group(1)
                .replaceAll("[^0-9]", "");

        if (phone.startsWith("00")) {
            phone = phone.substring(2);
        }

        // پیدا کردن متن پیام بعد از کلمه متن یا بگو
        Matcher messageMatcher = Pattern.compile(
                "(?:متن|بگو|text|say)\\s*[:：]?\\s*(.+)",
                Pattern.CASE_INSENSITIVE
        ).matcher(command);

        if (!messageMatcher.find()) {

            output.setText(
                    "متن پیام را هم بگو. مثال: متن سلام، حالت چطوره؟"
            );

            return true;
        }

        String messageText =
                messageMatcher.group(1).trim();

        try {

            String url =
                    "https://wa.me/" + phone
                    + "?text="
                    + URLEncoder.encode(messageText, "UTF-8");

            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
            );

            startActivity(intent);

            output.setText(
                    "گفت‌وگوی واتساپ باز شد. پیام را بررسی کن و خودت دکمه ارسال را بزن."
            );

        } catch (Exception e) {

            output.setText(
                    "خطا در باز کردن واتساپ: " + e.getMessage()
            );
        }

        return true;
    }

    @Override
    protected void onDestroy() {

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }
}

package com.termux.app.workspace;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Curated installer for optional Android-compatible Termux packages. */
public final class WorkspacePackageCenter {

    public interface CommandCallback {
        void onCommand(@NonNull String command);
    }

    private WorkspacePackageCenter() {}

    public static void show(@NonNull Activity activity, @NonNull CommandCallback callback) {
        try {
            String json = readAsset(activity, "hs-android-packages.json");
            JSONObject root = new JSONObject(json);
            JSONArray categories = root.getJSONArray("categories");
            String[] titles = new String[categories.length()];
            for (int i = 0; i < categories.length(); i++) {
                titles[i] = categories.getJSONObject(i).getString("title");
            }
            new AlertDialog.Builder(activity)
                .setTitle("Trung tâm package Android")
                .setItems(titles, (dialog, which) ->
                    showCategory(activity, categories.optJSONObject(which), callback))
                .setNegativeButton("Đóng", null)
                .show();
        } catch (Exception e) {
            Toast.makeText(activity, "Không đọc được danh mục package: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private static void showCategory(@NonNull Activity activity, @NonNull JSONObject category,
                                     @NonNull CommandCallback callback) {
        JSONArray packages = category.optJSONArray("packages");
        if (packages == null || packages.length() == 0) {
            Toast.makeText(activity, "Danh mục chưa có package", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[packages.length()];
        boolean[] checked = new boolean[packages.length()];
        for (int i = 0; i < packages.length(); i++) {
            JSONObject item = packages.optJSONObject(i);
            labels[i] = item.optString("name") + " — " + item.optString("description");
        }

        new AlertDialog.Builder(activity)
            .setTitle(category.optString("title") + " · Chọn package")
            .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("Tiếp tục", (d, which) -> {
                List<String> selected = new ArrayList<>();
                List<String> notes = new ArrayList<>();
                for (int i = 0; i < packages.length(); i++) {
                    if (checked[i]) {
                        JSONObject item = packages.optJSONObject(i);
                        String name = item.optString("name");
                        // Only allow safe package identifiers from the bundled catalog.
                        if (name.matches("[a-z0-9][a-z0-9+.-]*")) {
                            selected.add(name);
                            String note = item.optString("note");
                            if (!note.isEmpty()) notes.add(name + ": " + note);
                        }
                    }
                }
                if (selected.isEmpty()) {
                    Toast.makeText(activity, "Chưa chọn package nào", Toast.LENGTH_SHORT).show();
                    return;
                }
                String command = "pkg update && pkg install -y " + join(selected);
                StringBuilder message = new StringBuilder("Sẽ chạy lệnh:\n\n")
                    .append(command)
                    .append("\n\nPackage sẽ được tải từ repository Termux đang cấu hình.");
                for (String note : notes) message.append("\n\n• ").append(note);
                new AlertDialog.Builder(activity)
                    .setTitle("Xác nhận cài đặt")
                    .setMessage(message.toString())
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Gửi lệnh đến Terminal", (confirm, result) -> callback.onCommand(command))
                    .show();
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    private static String readAsset(@NonNull Activity activity, @NonNull String name) throws Exception {
        try (InputStream in = activity.getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String join(@NonNull List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(' ');
            result.append(value);
        }
        return result.toString();
    }
}

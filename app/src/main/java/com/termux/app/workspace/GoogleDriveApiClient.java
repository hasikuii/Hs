package com.termux.app.workspace;

import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure HttpURLConnection REST v3 client for Google Drive:
 * account info, browse folders/files, create folder, upload files/folders (resumable),
 * download files/folders, rename, and delete.
 */
public final class GoogleDriveApiClient {

    public static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";
    private static final String DRIVE_API_BASE = "https://www.googleapis.com/drive/v3";
    private static final String DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3";
    private static final int CHUNK_SIZE = 2 * 1024 * 1024; // 2 MiB resumable upload chunks

    public static final class DriveAccountInfo {
        public final String displayName;
        public final String email;
        public final long usedBytes;
        public final long limitBytes;

        public DriveAccountInfo(@NonNull String displayName, @NonNull String email, long usedBytes, long limitBytes) {
            this.displayName = displayName;
            this.email = email;
            this.usedBytes = usedBytes;
            this.limitBytes = limitBytes;
        }
    }

    public static final class DriveItem {
        public final String id;
        public final String name;
        public final String mimeType;
        public final long size;
        public final String modifiedTime;

        public DriveItem(@NonNull String id, @NonNull String name, @NonNull String mimeType,
                         long size, @NonNull String modifiedTime) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
            this.modifiedTime = modifiedTime;
        }

        public boolean isFolder() {
            return FOLDER_MIME_TYPE.equals(mimeType);
        }

        public boolean isGoogleWorkspaceDoc() {
            return mimeType.startsWith("application/vnd.google-apps.") && !isFolder();
        }
    }

    public interface TransferProgress {
        void onProgress(@NonNull String message);
    }

    private GoogleDriveApiClient() {}

    @NonNull
    public static DriveAccountInfo fetchAccountInfo(@NonNull String token) throws IOException {
        String url = DRIVE_API_BASE + "/about?fields=user(displayName,emailAddress),storageQuota(usage,limit)";
        JSONObject json = requestJson("GET", url, token, null, false);
        JSONObject user = json.optJSONObject("user");
        JSONObject quota = json.optJSONObject("storageQuota");

        String name = user != null ? user.optString("displayName", "Google Drive") : "Google Drive";
        String email = user != null ? user.optString("emailAddress", "") : "";
        long used = quota != null ? parseLongSafe(quota.optString("usage", "0")) : 0L;
        long limit = quota != null ? parseLongSafe(quota.optString("limit", "0")) : 0L;
        return new DriveAccountInfo(name, email, used, limit);
    }

    @NonNull
    public static List<DriveItem> listFiles(@NonNull String token,
                                            @NonNull String parentFolderId,
                                            @Nullable String searchQuery) throws IOException {
        String escapedParent = escapeQuery(parentFolderId);
        StringBuilder q = new StringBuilder();
        q.append("'").append(escapedParent).append("' in parents and trashed = false");
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            q.append(" and name contains '").append(escapeQuery(searchQuery.trim())).append("'");
        }

        String url = DRIVE_API_BASE + "/files"
            + "?q=" + URLEncoder.encode(q.toString(), "UTF-8")
            + "&orderBy=" + URLEncoder.encode("folder,name", "UTF-8")
            + "&pageSize=200"
            + "&fields=" + URLEncoder.encode("files(id,name,mimeType,size,modifiedTime)", "UTF-8");

        JSONObject json = requestJson("GET", url, token, null, false);
        JSONArray files = json.optJSONArray("files");
        List<DriveItem> result = new ArrayList<>();
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject obj = files.optJSONObject(i);
                if (obj == null) continue;
                result.add(parseDriveItem(obj));
            }
        }
        return result;
    }

    @NonNull
    public static DriveItem createFolder(@NonNull String token,
                                         @NonNull String folderName,
                                         @NonNull String parentFolderId) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("name", folderName);
            body.put("mimeType", FOLDER_MIME_TYPE);
            JSONArray parents = new JSONArray();
            parents.put(parentFolderId);
            body.put("parents", parents);
        } catch (JSONException e) {
            throw new IOException("JSON build error", e);
        }
        String url = DRIVE_API_BASE + "/files?fields=id,name,mimeType,size,modifiedTime";
        JSONObject created = requestJson("POST", url, token, body.toString(), false);
        return parseDriveItem(created);
    }

    @NonNull
    public static DriveItem findOrCreateFolder(@NonNull String token,
                                               @NonNull String folderName,
                                               @NonNull String parentFolderId) throws IOException {
        String q = "'" + escapeQuery(parentFolderId) + "' in parents and name = '"
            + escapeQuery(folderName) + "' and mimeType = '" + FOLDER_MIME_TYPE + "' and trashed = false";
        String url = DRIVE_API_BASE + "/files"
            + "?q=" + URLEncoder.encode(q, "UTF-8")
            + "&pageSize=1"
            + "&fields=" + URLEncoder.encode("files(id,name,mimeType,size,modifiedTime)", "UTF-8");

        JSONObject json = requestJson("GET", url, token, null, false);
        JSONArray files = json.optJSONArray("files");
        if (files != null && files.length() > 0) {
            JSONObject first = files.optJSONObject(0);
            if (first != null) return parseDriveItem(first);
        }
        return createFolder(token, folderName, parentFolderId);
    }

    @NonNull
    public static DriveItem uploadFile(@NonNull String token,
                                       @NonNull File localFile,
                                       @NonNull String parentFolderId,
                                       @Nullable TransferProgress progress) throws IOException {
        if (!localFile.exists() || !localFile.isFile()) {
            throw new IOException("Tệp không tồn tại: " + localFile.getName());
        }
        if (progress != null) {
            progress.onProgress("Đang tải lên: " + localFile.getName());
        }

        String mimeType = guessMimeType(localFile.getName());
        JSONObject meta = new JSONObject();
        try {
            meta.put("name", localFile.getName());
            JSONArray parents = new JSONArray();
            parents.put(parentFolderId);
            meta.put("parents", parents);
        } catch (JSONException e) {
            throw new IOException("JSON build error", e);
        }

        long totalBytes = localFile.length();
        // Initiate resumable upload session (handles empty, small, and large files reliably)
        String initUrl = DRIVE_UPLOAD_BASE + "/files?uploadType=resumable&fields=id,name,mimeType,size,modifiedTime";
        HttpURLConnection initConn = (HttpURLConnection) new URL(initUrl).openConnection();
        initConn.setConnectTimeout(20000);
        initConn.setReadTimeout(30000);
        initConn.setRequestMethod("POST");
        initConn.setDoOutput(true);
        initConn.setRequestProperty("Authorization", "Bearer " + token);
        initConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        initConn.setRequestProperty("X-Upload-Content-Type", mimeType);
        initConn.setRequestProperty("X-Upload-Content-Length", String.valueOf(totalBytes));

        byte[] metaBytes = meta.toString().getBytes(StandardCharsets.UTF_8);
        initConn.setFixedLengthStreamingMode(metaBytes.length);
        try (OutputStream os = initConn.getOutputStream()) {
            os.write(metaBytes);
        }

        int initCode = initConn.getResponseCode();
        if (initCode < 200 || initCode >= 300) {
            throw new IOException("Drive upload init failed (" + initCode + "): " + readError(initConn));
        }

        String sessionUri = initConn.getHeaderField("Location");
        if (sessionUri == null || sessionUri.isEmpty()) {
            throw new IOException("Drive API did not return upload session Location.");
        }

        if (totalBytes == 0L) {
            HttpURLConnection putConn = (HttpURLConnection) new URL(sessionUri).openConnection();
            putConn.setRequestMethod("PUT");
            putConn.setDoOutput(true);
            putConn.setFixedLengthStreamingMode(0);
            putConn.setRequestProperty("Content-Type", mimeType);
            int code = putConn.getResponseCode();
            String resp = readStream(code >= 200 && code < 300 ? putConn.getInputStream() : putConn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new IOException("Upload failed (" + code + "): " + resp);
            }
            try {
                return parseDriveItem(new JSONObject(resp));
            } catch (JSONException e) {
                throw new IOException("Invalid Drive response", e);
            }
        }

        byte[] buf = new byte[CHUNK_SIZE];
        long offset = 0L;
        String finalResponse = null;

        try (InputStream fis = new BufferedInputStream(new FileInputStream(localFile))) {
            while (offset < totalBytes) {
                int toRead = (int) Math.min(CHUNK_SIZE, totalBytes - offset);
                int readTotal = 0;
                while (readTotal < toRead) {
                    int n = fis.read(buf, readTotal, toRead - readTotal);
                    if (n < 0) break;
                    readTotal += n;
                }
                if (readTotal <= 0) break;

                long endByte = offset + readTotal - 1;
                HttpURLConnection chunkConn = (HttpURLConnection) new URL(sessionUri).openConnection();
                chunkConn.setConnectTimeout(25000);
                chunkConn.setReadTimeout(60000);
                chunkConn.setRequestMethod("PUT");
                chunkConn.setDoOutput(true);
                chunkConn.setFixedLengthStreamingMode(readTotal);
                chunkConn.setRequestProperty("Content-Type", mimeType);
                chunkConn.setRequestProperty("Content-Range", "bytes " + offset + "-" + endByte + "/" + totalBytes);

                try (OutputStream os = new BufferedOutputStream(chunkConn.getOutputStream())) {
                    os.write(buf, 0, readTotal);
                }

                int code = chunkConn.getResponseCode();
                if (code == 308) {
                    offset += readTotal;
                    if (progress != null) {
                        int pct = (int) ((offset * 100L) / totalBytes);
                        progress.onProgress("Đang tải lên " + localFile.getName() + " (" + pct + "%)");
                    }
                } else if (code == 200 || code == 201) {
                    finalResponse = readStream(chunkConn.getInputStream());
                    offset += readTotal;
                    break;
                } else {
                    throw new IOException("Upload failed (" + code + "): " + readError(chunkConn));
                }
            }
        }

        if (finalResponse == null) {
            throw new IOException("Upload did not complete.");
        }
        try {
            return parseDriveItem(new JSONObject(finalResponse));
        } catch (JSONException e) {
            throw new IOException("Invalid Drive response", e);
        }
    }

    public static int uploadFolderRecursively(@NonNull String token,
                                              @NonNull File localDir,
                                              @NonNull String parentDriveFolderId,
                                              @Nullable TransferProgress progress) throws IOException {
        if (!localDir.isDirectory()) return 0;
        if (progress != null) {
            progress.onProgress("Tạo thư mục trên Drive: " + localDir.getName());
        }
        DriveItem driveFolder = createFolder(token, localDir.getName(), parentDriveFolderId);
        int count = 0;
        File[] children = localDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    count += uploadFolderRecursively(token, child, driveFolder.id, progress);
                } else if (child.isFile()) {
                    uploadFile(token, child, driveFolder.id, progress);
                    count++;
                }
            }
        }
        return count;
    }

    @NonNull
    public static File downloadFile(@NonNull String token,
                                    @NonNull DriveItem item,
                                    @NonNull File destDir,
                                    @Nullable TransferProgress progress) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("Không thể tạo thư mục lưu tệp");
        }

        String outName = item.name;
        String url;
        if (item.isGoogleWorkspaceDoc()) {
            if (!outName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                outName = outName + ".pdf";
            }
            url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(item.id, "UTF-8")
                + "/export?mimeType=" + URLEncoder.encode("application/pdf", "UTF-8");
        } else {
            url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(item.id, "UTF-8") + "?alt=media";
        }

        if (progress != null) {
            progress.onProgress("Đang tải xuống: " + outName);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Download failed (" + code + "): " + readError(conn));
        }

        File target = new File(destDir, outName);
        byte[] buf = new byte[32768];
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        return target;
    }

    public static int downloadFolderRecursively(@NonNull String token,
                                                @NonNull DriveItem driveFolder,
                                                @NonNull File localParentDir,
                                                @Nullable TransferProgress progress) throws IOException {
        File localSubDir = new File(localParentDir, driveFolder.name);
        if (!localSubDir.exists() && !localSubDir.mkdirs()) {
            throw new IOException("Không thể tạo thư mục: " + localSubDir.getName());
        }
        if (progress != null) {
            progress.onProgress("Đang tải thư mục: " + driveFolder.name);
        }
        int count = 0;
        List<DriveItem> children = listFiles(token, driveFolder.id, null);
        for (DriveItem child : children) {
            if (child.isFolder()) {
                count += downloadFolderRecursively(token, child, localSubDir, progress);
            } else {
                downloadFile(token, child, localSubDir, progress);
                count++;
            }
        }
        return count;
    }

    public static void renameFile(@NonNull String token, @NonNull String fileId, @NonNull String newName) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("name", newName);
        } catch (JSONException e) {
            throw new IOException("JSON build error", e);
        }
        String url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(fileId, "UTF-8");
        requestJson("POST", url, token, body.toString(), true);
    }

    public static void deleteFile(@NonNull String token, @NonNull String fileId) throws IOException {
        String url = DRIVE_API_BASE + "/files/" + URLEncoder.encode(fileId, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("DELETE");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Delete failed (" + code + "): " + readError(conn));
        }
    }

    @NonNull
    private static JSONObject requestJson(@NonNull String method,
                                          @NonNull String urlString,
                                          @NonNull String token,
                                          @Nullable String jsonBody,
                                          boolean patchOverride) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/json");
        if (patchOverride) {
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        }
        if (jsonBody != null) {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("Drive API error (" + code + "): " + readError(conn));
        }
        String resp = readStream(conn.getInputStream());
        if (resp.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(resp);
        } catch (JSONException e) {
            throw new IOException("Invalid JSON response", e);
        }
    }

    @NonNull
    private static DriveItem parseDriveItem(@NonNull JSONObject obj) {
        return new DriveItem(
            obj.optString("id", ""),
            obj.optString("name", "untitled"),
            obj.optString("mimeType", "application/octet-stream"),
            parseLongSafe(obj.optString("size", "0")),
            obj.optString("modifiedTime", "")
        );
    }

    private static long parseLongSafe(@Nullable String val) {
        if (val == null || val.isEmpty()) return 0L;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @NonNull
    private static String escapeQuery(@NonNull String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    @NonNull
    private static String guessMimeType(@NonNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".gz")) return "application/gzip";
        if (lower.endsWith(".tar")) return "application/x-tar";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        int dot = lower.lastIndexOf('.');
        if (dot > 0 && dot < lower.length() - 1) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substring(dot + 1));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @NonNull
    private static String readError(@NonNull HttpURLConnection conn) {
        try {
            InputStream err = conn.getErrorStream();
            if (err != null) {
                return readStream(err);
            }
            String msg = conn.getResponseMessage();
            return msg != null ? msg : "Unknown error";
        } catch (IOException e) {
            return e.getMessage() != null ? e.getMessage() : "Unknown error";
        }
    }

    @NonNull
    private static String readStream(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        try (InputStream input = in) {
            while ((len = input.read(buf)) > 0) {
                baos.write(buf, 0, len);
            }
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}

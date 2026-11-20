package com.termux.app.workspace;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;

import java.util.Collections;

/**
 * Handles Google Drive OAuth 2.0 authorization via Google Identity Services AuthorizationClient.
 */
public final class GoogleDriveAuthHelper {

    public static final int REQUEST_CODE_DRIVE_AUTH = 7402;
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";

    public interface AuthCallback {
        void onAuthorized(@NonNull String accessToken);
        void onAuthFailed(@NonNull String errorMessage);
    }

    private static GoogleDriveAuthHelper sInstance;
    private String mCachedToken;
    private long mTokenObtainedAtMs;
    private AuthCallback mPendingCallback;

    private GoogleDriveAuthHelper() {}

    @NonNull
    public static synchronized GoogleDriveAuthHelper getInstance() {
        if (sInstance == null) {
            sInstance = new GoogleDriveAuthHelper();
        }
        return sInstance;
    }

    @Nullable
    public synchronized String getCachedToken() {
        // Access tokens last ~1 hour; treat < 45 mins as still fresh
        if (mCachedToken != null && !mCachedToken.isEmpty()
            && (System.currentTimeMillis() - mTokenObtainedAtMs) < 45L * 60L * 1000L) {
            return mCachedToken;
        }
        return null;
    }

    public synchronized void clearSession() {
        mCachedToken = null;
        mTokenObtainedAtMs = 0L;
        mPendingCallback = null;
    }

    public void authorize(@NonNull Activity activity, boolean forceRefresh, @NonNull AuthCallback callback) {
        if (!forceRefresh) {
            String cached = getCachedToken();
            if (cached != null) {
                callback.onAuthorized(cached);
                return;
            }
        }

        AuthorizationRequest request = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(DRIVE_SCOPE)))
            .build();

        AuthorizationClient client = Identity.getAuthorizationClient(activity);
        client.authorize(request)
            .addOnSuccessListener(result -> {
                if (result.hasResolution() && result.getPendingIntent() != null) {
                    synchronized (GoogleDriveAuthHelper.this) {
                        mPendingCallback = callback;
                    }
                    try {
                        PendingIntent pendingIntent = result.getPendingIntent();
                        activity.startIntentSenderForResult(
                            pendingIntent.getIntentSender(),
                            REQUEST_CODE_DRIVE_AUTH,
                            null, 0, 0, 0
                        );
                    } catch (IntentSender.SendIntentException e) {
                        synchronized (GoogleDriveAuthHelper.this) {
                            mPendingCallback = null;
                        }
                        callback.onAuthFailed("Không thể mở hộp thoại cấp quyền Google Drive: " + e.getMessage());
                    }
                } else {
                    String token = result.getAccessToken();
                    if (token != null && !token.isEmpty()) {
                        synchronized (GoogleDriveAuthHelper.this) {
                            mCachedToken = token;
                            mTokenObtainedAtMs = System.currentTimeMillis();
                        }
                        callback.onAuthorized(token);
                    } else {
                        callback.onAuthFailed("Không nhận được Access Token từ Google Drive.");
                    }
                }
            })
            .addOnFailureListener(e -> {
                String msg = formatAuthError(e);
                callback.onAuthFailed(msg);
            });
    }

    public void handleActivityResult(@NonNull Activity activity, int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_CODE_DRIVE_AUTH) return;

        AuthCallback callback;
        synchronized (this) {
            callback = mPendingCallback;
            mPendingCallback = null;
        }
        if (callback == null) return;

        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onAuthFailed("Bạn đã hủy đăng nhập Google Drive.");
            return;
        }

        try {
            AuthorizationResult result = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data);
            String token = result.getAccessToken();
            if (token != null && !token.isEmpty()) {
                synchronized (this) {
                    mCachedToken = token;
                    mTokenObtainedAtMs = System.currentTimeMillis();
                }
                callback.onAuthorized(token);
            } else {
                callback.onAuthFailed("Không lấy được mã truy cập Google Drive.");
            }
        } catch (ApiException e) {
            callback.onAuthFailed(formatAuthError(e));
        }
    }

    @NonNull
    private static String formatAuthError(@NonNull Exception e) {
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getStatusCode();
            if (code == CommonStatusCodes.CANCELED) {
                return "Đã hủy xác thực Google Drive.";
            }
            if (code == CommonStatusCodes.NETWORK_ERROR) {
                return "Lỗi mạng khi kết nối Google Drive.";
            }
            return "Lỗi xác thực Google Drive (mã " + code + "): " + e.getMessage();
        }
        return "Lỗi xác thực Google Drive: " + e.getMessage();
    }
}

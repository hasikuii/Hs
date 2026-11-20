package com.termux.app.workspace;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Handles Google Drive OAuth 2.0 authorization via Google Identity Services AuthorizationClient,
 * including granted-scope verification and cached-token invalidation on 401/403 errors.
 */
public final class GoogleDriveAuthHelper {

    public static final int REQUEST_CODE_DRIVE_AUTH = 7402;
    public static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    public static final String DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file";

    public interface AuthCallback {
        void onAuthorized(@NonNull String accessToken);
        void onAuthFailed(@NonNull String errorMessage);
    }

    private static GoogleDriveAuthHelper sInstance;
    private String mCachedToken;
    private long mTokenObtainedAtMs;
    private String mActiveScope = DRIVE_SCOPE;
    private AuthCallback mPendingCallback;

    private GoogleDriveAuthHelper() {}

    @NonNull
    public static synchronized GoogleDriveAuthHelper getInstance() {
        if (sInstance == null) {
            sInstance = new GoogleDriveAuthHelper();
        }
        return sInstance;
    }

    @NonNull
    public synchronized String getActiveScope() {
        return mActiveScope;
    }

    public synchronized void setUseDriveFileScopeOnly(boolean useDriveFileOnly) {
        String target = useDriveFileOnly ? DRIVE_FILE_SCOPE : DRIVE_SCOPE;
        if (!target.equals(mActiveScope)) {
            mActiveScope = target;
            mCachedToken = null;
            mTokenObtainedAtMs = 0L;
        }
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

    public void invalidateSession(@NonNull Context context) {
        final String oldToken;
        synchronized (this) {
            oldToken = mCachedToken;
            mCachedToken = null;
            mTokenObtainedAtMs = 0L;
            mPendingCallback = null;
        }
        if (oldToken != null && !oldToken.isEmpty()) {
            final Context appCtx = context.getApplicationContext();
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    GoogleAuthUtil.clearToken(appCtx, oldToken);
                } catch (Exception ignored) {
                }
            });
        }
    }

    public void authorize(@NonNull Activity activity, boolean forceRefresh, @NonNull AuthCallback callback) {
        if (!forceRefresh) {
            String cached = getCachedToken();
            if (cached != null) {
                callback.onAuthorized(cached);
                return;
            }
        } else {
            invalidateSession(activity);
        }

        final String requestedScope = getActiveScope();
        AuthorizationRequest request = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(requestedScope)))
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
                    handleAuthorizationResult(activity, result, requestedScope, callback);
                }
            })
            .addOnFailureListener(e -> callback.onAuthFailed(formatAuthError(e)));
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
            handleAuthorizationResult(activity, result, getActiveScope(), callback);
        } catch (ApiException e) {
            callback.onAuthFailed(formatAuthError(e));
        }
    }

    private void handleAuthorizationResult(@NonNull Context context,
                                           @NonNull AuthorizationResult result,
                                           @NonNull String requestedScope,
                                           @NonNull AuthCallback callback) {
        List<String> granted = result.getGrantedScopes();
        if (granted != null && !granted.isEmpty() && !hasDriveScope(granted)) {
            invalidateSession(context);
            callback.onAuthFailed(
                "Bạn chưa tích chọn quyền Google Drive trên hộp thoại xác nhận của Google.\n"
                    + "Vui lòng bấm \"Kết nối\" lại và tích vào ô quyền truy cập Google Drive.");
            return;
        }

        String token = result.getAccessToken();
        if (token != null && !token.isEmpty()) {
            synchronized (this) {
                mCachedToken = token;
                mTokenObtainedAtMs = System.currentTimeMillis();
            }
            callback.onAuthorized(token);
        } else {
            callback.onAuthFailed("Không nhận được Access Token từ Google Drive (scope: " + requestedScope + ").");
        }
    }

    private static boolean hasDriveScope(@NonNull List<String> grantedScopes) {
        for (String scope : grantedScopes) {
            if (DRIVE_SCOPE.equals(scope) || DRIVE_FILE_SCOPE.equals(scope)) {
                return true;
            }
        }
        return false;
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
            if (code == CommonStatusCodes.DEVELOPER_ERROR) {
                return "Lỗi cấu hình OAuth (DEVELOPER_ERROR 10): Kiểm tra Android OAuth Client (package com.termux + SHA-1 testkey) trên Google Cloud Console.";
            }
            return "Lỗi xác thực Google Drive (mã " + code + "): " + e.getMessage();
        }
        return "Lỗi xác thực Google Drive: " + e.getMessage();
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Builds a TLS 1.3 {@code SSLContext} that presents the ADB RSA keypair as a
 * self-signed client certificate and accepts any peer certificate (adbd in
 * wireless-debugging mode validates the client by its authorized public key,
 * not by a CA chain — matching AOSP {@code tls::TlsConnection}'s
 * {@code SetCertVerifyCallback -> 1}).
 */
final class SslUtils {
    private static boolean sCustomConscrypt = false;

    private SslUtils() {
    }

    /** True if a bundled org.conscrypt provider was used (we always use the platform one). */
    static boolean isCustomConscrypt() {
        return sCustomConscrypt;
    }

    static SSLContext getSslContext(KeyPair keyPair) throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext ctx;
        try {
            Class<?> providerClass = Class.forName("com.android.org.conscrypt.OpenSSLProvider");
            Provider provider = (Provider) providerClass.getDeclaredConstructor().newInstance();
            ctx = SSLContext.getInstance("TLSv1.3", provider);
        } catch (Throwable t) {
            ctx = SSLContext.getInstance("TLSv1.3");
        }
        ctx.init(new KeyManager[]{new AdbKeyManagerDelegate(keyPair)},
                new X509TrustManager[]{new AcceptAllTrustManager()},
                new SecureRandom());
        return ctx;
    }

    private static final class AdbKeyManagerDelegate extends X509ExtendedKeyManager {
        private static final String ALIAS = "adb";
        private final KeyPair mKeyPair;

        AdbKeyManagerDelegate(KeyPair keyPair) {
            mKeyPair = keyPair;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            for (String kt : keyTypes) {
                if (RSA".equals(kt)) {
                    return ALIAS;
                }
            }
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            if (ALIAS.equals(alias)) {
                return new X509Certificate[]{(X509Certificate) mKeyPair.getCertificate()};
            }
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? mKeyPair.getPrivateKey() : null;
        }
    }

    private static final class AcceptAllTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

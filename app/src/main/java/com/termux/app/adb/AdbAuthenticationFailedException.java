// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

public class AdbAuthenticationFailedException extends Exception {
    public AdbAuthenticationFailedException() {
        super("ADB authentication failed: the peer rejected the public key.");
    }

    public AdbAuthenticationFailedException(String message) {
        super(message);
    }
}

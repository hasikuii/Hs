// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

public class AdbPairingRequiredException extends Exception {
    public AdbPairingRequiredException() {
        super("ADB pairing is required before connecting.");
    }

    public AdbPairingRequiredException(String message) {
        super(message);
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0
package com.termux.app.adb;

import java.nio.charset.Charset;

/** Tiny String.getBytes helper avoiding java.nio.charset.StandardCharsets on very old APIs. */
final class StringCompat {
    private StringCompat() {
    }

    static byte[] getBytes(String text, String charsetName) {
        return text.getBytes(Charset.forName(charsetName));
    }
}

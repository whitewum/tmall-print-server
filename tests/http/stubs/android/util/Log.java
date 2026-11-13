// SPDX-License-Identifier: GPL-2.0-or-later
package android.util;
public final class Log {
    public static int i(String tag, String message) { System.err.println(tag + ": " + message); return 0; }
    public static int w(String tag, String message) { System.err.println(tag + ": " + message); return 0; }
    public static int w(String tag, String message, Throwable error) {
        System.err.println(tag + ": " + message + " (" + error + ")"); return 0;
    }
}

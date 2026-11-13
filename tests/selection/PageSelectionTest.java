// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;
import java.io.IOException;
import java.util.Arrays;
public class PageSelectionTest {
    private static int checked;
    private static void ok(String value, int count, int copies, int... expected) throws Exception {
        int[] actual = PageSelection.parse(value, count, copies);
        if (!Arrays.equals(actual, expected)) throw new AssertionError(value + ": " + Arrays.toString(actual));
        checked++;
    }
    private static void bad(String value, int count, int copies) throws Exception {
        try { PageSelection.parse(value, count, copies); }
        catch (IOException expected) { checked++; return; }
        throw new AssertionError("Accepted invalid range " + value);
    }
    public static void main(String[] args) throws Exception {
        ok(null, 3, 1, 0, 1, 2);
        ok("", 1, 10, 0);
        ok("2", 5, 1, 1);
        ok("1-3", 5, 1, 0, 1, 2);
        ok("1,3-5", 5, 1, 0, 2, 3, 4);
        ok(" 1， 3 - 4 ", 5, 1, 0, 2, 3);
        ok("3,1-3", 5, 1, 2, 0, 1);
        ok("1000", 1000, 10, 999);
        ok("01", 5, 1, 0);
        ok("1-1", 5, 1, 0);
        bad("", 101, 1);
        bad("", 11, 10);
        bad("0", 5, 1);
        bad("6", 5, 1);
        bad("1-6", 5, 1);
        bad("3-1", 5, 1);
        bad("1,", 5, 1);
        bad(",1", 5, 1);
        bad("1,,2", 5, 1);
        bad("-1", 5, 1);
        bad("1;2", 5, 1);
        bad("1-2-3", 5, 1);
        bad("abc", 5, 1);
        bad("2147483648", 5, 1);
        bad("1-11", 100, 10);
        bad("1", 5, 0);
        bad("1", 5, 11);
        bad("1", 0, 1);
        StringBuilder longInput = new StringBuilder();
        for (int i=0;i<201;i++) longInput.append('1');
        bad(longInput.toString(), 100, 1);
        System.out.println("Page selection: " + checked + "/" + checked + " passed");
    }
}

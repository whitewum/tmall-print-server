// SPDX-License-Identifier: GPL-2.0-or-later
package android.content;
import java.io.File;
public class Context {
    private final File cache;
    public Context(File cache) { this.cache = cache; }
    public Context getApplicationContext() { return this; }
    public File getCacheDir() { return cache; }
}

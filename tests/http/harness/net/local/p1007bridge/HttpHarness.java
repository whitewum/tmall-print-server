// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;
import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
public final class HttpHarness {
    public static void main(String[] args) throws Exception {
        final File cache = new File(args[0]);
        cache.mkdirs();
        final AtomicInteger rendered = new AtomicInteger(), sent = new AtomicInteger();
        WebPrintServer server = new WebPrintServer(new Context(cache),
            new WebPrintServer.Renderer() {
                public File render(File input, String mime, int copies, String pages) throws Exception {
                    System.err.println("RENDER_PAGES=" + pages);
                    rendered.incrementAndGet();
                    byte[] original = Files.readAllBytes(input.toPath());
                    File prn = File.createTempFile("fake-render-", ".prn", cache);
                    FileOutputStream out = new FileOutputStream(prn);
                    out.write(original); out.close(); return prn;
                }
            }, new WebPrintServer.Sender() {
                public void send(File prn) throws Exception {
                    if (Files.readAllBytes(prn.toPath()).length == 0) throw new IOException("空测试文件");
                    sent.incrementAndGet(); // No printer, USB, ADB or network sender involved.
                }
            });
        server.setStatusProvider(new WebPrintServer.Status() {
            public String get() { return "模拟打印服务（不会实际打印）"; }
        });
        server.start();
        System.out.println("READY"); System.out.flush();
        try { System.in.read(); }
        finally { server.stop(); }
        System.out.println("COUNTS " + rendered.get() + " " + sent.get());
    }
}

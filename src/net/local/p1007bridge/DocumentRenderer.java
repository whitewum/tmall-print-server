// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/** Renders one bounded raster strip at a time, then encodes with foo2xqx. */
final class DocumentRenderer implements WebPrintServer.Renderer {
    private static final int WIDTH = 9920, HEIGHT = 7016, STRIP = 128, MARGIN = 118;
    private static final long MAX_OUTPUT = 64L * 1024 * 1024;
    private static final int[] BAYER = {
        0,32,8,40,2,34,10,42,48,16,56,24,50,18,58,26,
        12,44,4,36,14,46,6,38,60,28,52,20,62,30,54,22,
        3,35,11,43,1,33,9,41,51,19,59,27,49,17,57,25,
        15,47,7,39,13,45,5,37,63,31,55,23,61,29,53,21
    };
    private final Context context;
    private final File encoder;

    DocumentRenderer(Context context) throws IOException {
        this.context = context;
        encoder = new File(context.getDir("encoder", 0), "foo2xqx");
        try (InputStream in = context.getAssets().open("foo2xqx-armeabi-v7a");
             OutputStream out = new FileOutputStream(encoder)) {
            copy(in, out, 2L * 1024 * 1024);
        }
        if (!encoder.setExecutable(true, true)) throw new IOException("无法准备打印驱动");
    }

    @Override public File render(File input, String mime, int copies, String pages) throws Exception {
        if (copies < 1 || copies > 10) throw new IOException("份数必须在 1 到 10 之间");
        final File result = File.createTempFile("encoded-", ".prn", input.getParentFile());
        final Process process = new ProcessBuilder(encoder.getAbsolutePath(), "-r1200x600",
                "-g9920x7016", "-p9", "-s7", "-u176x84", "-l176x84", "-n" + copies).start();
        final AtomicReference<IOException> failure = new AtomicReference<IOException>();
        final ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread stdout = new Thread(new Runnable() {
            @Override public void run() {
                try (InputStream in = process.getInputStream(); OutputStream out = new FileOutputStream(result)) {
                    copy(in, out, MAX_OUTPUT);
                } catch (IOException e) { failure.compareAndSet(null, e); process.destroy(); }
            }
        }, "print-encoder-output");
        Thread stderr = new Thread(new Runnable() {
            @Override public void run() {
                try (InputStream in = process.getErrorStream()) {
                    byte[] b = new byte[1024]; int n;
                    while ((n = in.read(b)) != -1) if (errors.size() < 8192)
                        errors.write(b, 0, Math.min(n, 8192 - errors.size()));
                } catch (IOException ignored) { }
            }
        }, "print-encoder-log");
        Thread timeout = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(600000);
                    failure.compareAndSet(null, new IOException("文档处理超过十分钟，请减少页数后重试"));
                    process.destroy();
                } catch (InterruptedException finished) { }
            }
        }, "print-encoder-timeout");
        stdout.start(); stderr.start(); timeout.start();
        boolean success = false;
        try {
            try (OutputStream out = new BufferedOutputStream(process.getOutputStream(), 65536)) {
                if ("application/pdf".equals(mime)) renderPdf(input, copies, pages, out);
                else if ("image/jpeg".equals(mime) || "image/png".equals(mime)) { PageSelection.parse(pages, 1, copies); renderImage(input, out); }
                else throw new IOException("仅支持 PDF、JPG 和 PNG");
            }
            int code = process.waitFor();
            stdout.join(5000); stderr.join(5000);
            if (stdout.isAlive()) throw new IOException("打印数据未完成");
            if (failure.get() != null) throw failure.get();
            if (code != 0 || result.length() < 32) {
                Log.w("P1007Bridge", "Encoder exited " + code + ": " + errors.toString("UTF-8"));
                throw new IOException("打印驱动未能完成转换");
            }
            success = true;
            return result;
        } finally {
            timeout.interrupt();
            process.destroy();
            try { stdout.join(5000); stderr.join(5000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            if (!success) result.delete();
        }
    }

    private void renderPdf(File file, int copies, String pages, OutputStream out) throws IOException {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer pdf = null;
        try {
            pdf = new PdfRenderer(fd);
            int count = pdf.getPageCount();
            int[] selected = PageSelection.parse(pages, count, copies);
            Log.i("P1007Web", "Rendering " + selected.length + " of " + count + " PDF pages; copies=" + copies);
            for (int pageIndex : selected) {
                final PdfRenderer.Page page = pdf.openPage(pageIndex);
                try {
                    raster(out, page.getWidth(), page.getHeight(), new Painter() {
                        @Override public void paint(Bitmap bitmap, Matrix matrix) {
                            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        }
                    });
                } finally { page.close(); }
            }
        } catch (SecurityException e) { throw new IOException("无法打开此 PDF，请移除密码保护后重试", e); }
        catch (IllegalArgumentException e) { throw new IOException("PDF 文件无法读取", e); }
        finally { if (pdf != null) pdf.close(); else fd.close(); }
    }

    private void renderImage(File file, OutputStream out) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0
                || options.outWidth > 40000 || options.outHeight > 40000) throw new IOException("图片无法读取或尺寸过大");
        int sample = 1;
        while ((long)(options.outWidth / sample) * (options.outHeight / sample) > 8000000L) sample *= 2;
        options.inSampleSize = sample;
        options.inJustDecodeBounds = false;
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded == null) throw new IOException("图片无法解码");
        Bitmap oriented = decoded;
        try {
            try {
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                Matrix rotate = new Matrix();
                if (orientation == 3) rotate.setRotate(180);
                else if (orientation == 6) rotate.setRotate(90);
                else if (orientation == 8) rotate.setRotate(270);
                else if (orientation == 2) rotate.setScale(-1, 1);
                else if (orientation == 4) rotate.setScale(1, -1);
                else if (orientation == 5) { rotate.setRotate(90); rotate.postScale(-1, 1); }
                else if (orientation == 7) { rotate.setRotate(270); rotate.postScale(-1, 1); }
                if (!rotate.isIdentity()) oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), rotate, true);
            } catch (IOException noExif) { }
            final Bitmap image = oriented;
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            raster(out, image.getWidth(), image.getHeight(), new Painter() {
                @Override public void paint(Bitmap bitmap, Matrix matrix) {
                    new Canvas(bitmap).drawBitmap(image, matrix, paint);
                }
            });
        } finally { if (oriented != decoded) oriented.recycle(); decoded.recycle(); }
    }

    private interface Painter { void paint(Bitmap bitmap, Matrix matrix); }
    private void raster(OutputStream out, int width, int height, Painter painter) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("页面尺寸无效");
        out.write(("P4\n" + WIDTH + " " + HEIGHT + "\n").getBytes("US-ASCII"));
        // P1007's upstream mode is 1200x600: two horizontal samples per 600-dpi video pixel.
        float scale = Math.min((WIDTH - 4f * MARGIN) / (2f * width), (HEIGHT - 2f * MARGIN) / height);
        float left = (WIDTH - width * scale * 2) / 2, top = (HEIGHT - height * scale) / 2;
        Bitmap strip = Bitmap.createBitmap(WIDTH, STRIP, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[WIDTH];
        byte[] row = new byte[(WIDTH + 7) / 8];
        try {
            for (int y = 0; y < HEIGHT; y += STRIP) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("文档处理已停止");
                strip.eraseColor(Color.WHITE);
                Matrix matrix = new Matrix();
                matrix.setScale(scale * 2, scale);
                matrix.postTranslate(left, top - y);
                painter.paint(strip, matrix);
                int lines = Math.min(STRIP, HEIGHT - y);
                for (int local = 0; local < lines; local++) {
                    strip.getPixels(pixels, 0, WIDTH, 0, local, WIDTH, 1);
                    java.util.Arrays.fill(row, (byte)0);
                    int bayerY = ((y + local) & 7) * 8;
                    for (int x = 0; x < WIDTH; x++) {
                        int color = pixels[x];
                        int gray = (((color >> 16) & 255) * 77 + ((color >> 8) & 255) * 150 + (color & 255) * 29) >> 8;
                        int alpha = color >>> 24;
                        gray = (gray * alpha + 255 * (255 - alpha) + 127) / 255;
                        if (gray < BAYER[bayerY + (x & 7)] * 4 + 2) row[x >> 3] |= (byte)(0x80 >> (x & 7));
                    }
                    out.write(row);
                }
            }
        } finally { strip.recycle(); }
    }
    private static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[16384]; int n; long size = 0;
        while ((n = in.read(buffer)) != -1) {
            size += n;
            if (size > limit) throw new IOException("转换后的打印数据过大，请减少页数或图片尺寸");
            out.write(buffer, 0, n);
        }
    }
}

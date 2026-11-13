// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Small local-only HTTP server. Renderer returns a disposable PRN cache file. */
public final class WebPrintServer {
    public interface Renderer { File render(File input, String mime, int copies, String pages) throws Exception; }
    public interface Sender { void send(File prn) throws Exception; }
    public interface Status { String get(); }
    private static final int PORT = 8080, MAX_UPLOAD = 20 * 1024 * 1024;
    private static final String TAG = "P1007Web";
    private final Context context;
    private final Renderer renderer;
    private final Sender sender;
    private volatile Status statusProvider;
    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;
    private ThreadPoolExecutor http;
    private ExecutorService jobs;
    private final Semaphore capacity = new Semaphore(5);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
    private final List<Job> recent = new ArrayList<Job>();
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile String token = "";

    public WebPrintServer(Context context, Renderer renderer, Sender sender) {
        if (context == null || renderer == null || sender == null) throw new IllegalArgumentException();
        this.context = context.getApplicationContext();
        this.renderer = renderer;
        this.sender = sender;
    }
    public void setStatusProvider(Status provider) { statusProvider = provider; }
    public synchronized void start() throws IOException {
        if (running) return;
        token = makeToken();
        ServerSocket opened = new ServerSocket();
        try { opened.setReuseAddress(true); opened.bind(new InetSocketAddress(PORT), 8); }
        catch (IOException e) { opened.close(); throw e; }
        listener = opened;
        http = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(8), named("web-http"), new ThreadPoolExecutor.AbortPolicy());
        jobs = Executors.newSingleThreadExecutor(named("web-print"));
        running = true;
        final long session = epoch.incrementAndGet();
        final ExecutorService jobWorkers = jobs;
        final ServerSocket server = opened;
        final ThreadPoolExecutor workers = http;
        acceptThread = new Thread(new Runnable() { @Override public void run() { accept(server, workers, jobWorkers, session); } }, "web-accept");
        acceptThread.start();
        Log.i(TAG, "Web print listening on 8080");
    }
    public synchronized void stop() {
        running = false;
        epoch.incrementAndGet();
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        listener = null;
        for (Socket s : sockets) close(s);
        if (http != null) http.shutdownNow();
        if (jobs != null) {
            for (Runnable task : jobs.shutdownNow()) if (task instanceof JobTask) ((JobTask)task).cancel();
        }
    }
    private static ThreadFactory named(final String name) {
        return new ThreadFactory() { @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, name); t.setDaemon(true); return t;
        }};
    }
    private boolean active(long session) { return running && epoch.get() == session; }
    private void accept(ServerSocket server, ThreadPoolExecutor workers, final ExecutorService jobWorkers, final long session) {
        while (active(session) && !server.isClosed()) {
            try {
                final Socket socket = server.accept();
                socket.setSoTimeout(15000);
                if (!privateAddress(socket.getInetAddress())) { close(socket); continue; }
                sockets.add(socket);
                try { workers.execute(new Runnable() { @Override public void run() { handle(socket, jobWorkers, session); } }); }
                catch (RejectedExecutionException full) {
                    try { reply(socket, 503, "application/json; charset=utf-8", error("服务繁忙，请稍后重试")); }
                    catch (IOException ignored) { }
                    sockets.remove(socket); close(socket);
                }
            } catch (IOException e) { if (running && !server.isClosed()) Log.w(TAG, "HTTP accept", e); }
        }
    }
    static boolean privateAddress(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int x = b[0] & 255, y = b[1] & 255;
            return x == 10 || (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 168);
        }
        return b.length == 16 && (((b[0] & 254) == 252) || ((b[0] & 255) == 254 && (b[1] & 192) == 128));
    }
    private void handle(Socket socket, ExecutorService jobWorkers, long session) {
        File input = null;
        boolean reserved = false, queued = false;
        Job job = null;
        try {
            Request request = readRequest(socket.getInputStream());
            if (!validHost(request.headers.get("host"), socket.getLocalAddress())) throw new HttpError(403, "请使用魔盒的局域网 IP 地址访问");
            String origin = request.headers.get("origin");
            if (origin != null && !origin.equals("http://" + request.headers.get("host"))) throw new HttpError(403, "不允许跨站请求");
            if (request.headers.containsKey("transfer-encoding")) throw new HttpError(400, "不支持分块上传");
            if (request.headers.containsKey("expect")) throw new HttpError(417, "不支持该上传方式");
            String[] target = request.target.split("\\?", 2);
            String path = target[0];
            if ("GET".equals(request.method)) {
                if (request.headers.containsKey("content-length") && !"0".equals(request.headers.get("content-length"))) throw new HttpError(400, "无效请求");
                if ("/".equals(path)) reply(socket, 200, "text/html; charset=utf-8", WebPage.html(token));
                else if ("/api/jobs".equals(path)) reply(socket, 200, "application/json; charset=utf-8", jobList());
                else if ("/api/status".equals(path)) reply(socket, 200, "application/json; charset=utf-8", serviceStatus());
                else throw new HttpError(404, "页面不存在");
                return;
            }
            if (!"POST".equals(request.method) || !"/api/jobs".equals(path)) throw new HttpError(405, "不支持的请求");
            if (!constantEquals(token, request.headers.get("x-print-token"))) throw new HttpError(403, "页面已过期，请刷新后重试");
            String fetchSite = request.headers.get("sec-fetch-site");
            if (fetchSite != null && !"same-origin".equals(fetchSite) && !"none".equals(fetchSite)) throw new HttpError(403, "不允许跨站请求");
            int length = contentLength(request.headers.get("content-length"));
            Options options = options(target.length == 2 ? target[1] : "");
            String mime = request.headers.get("content-type");
            if (mime == null || !(mime.equals("application/pdf") || mime.equals("image/jpeg") || mime.equals("image/png"))) throw new HttpError(415, "只支持 PDF、JPG 和 PNG 文件");
            String name = fileName(request.headers.get("x-file-name"), mime);
            if (!active(session)) throw new HttpError(503, "服务已停止");
            if (!capacity.tryAcquire()) throw new HttpError(429, "已有 5 个任务，请等待后重试");
            reserved = true;
            input = File.createTempFile("web-job-", ".upload", context.getCacheDir());
            job = new Job(sequence.incrementAndGet(), name, options.copies, options.pages);
            add(job);
            OutputStream out = new FileOutputStream(input);
            try {
                byte[] buffer = new byte[16384];
                InputStream in = socket.getInputStream();
                int remaining = length;
                long deadline = System.nanoTime() + 120000000000L;
                while (remaining > 0) {
                    if (!active(session) || Thread.currentThread().isInterrupted()) throw new IOException("stopped");
                    if (System.nanoTime() > deadline) throw new HttpError(408, "上传超时，请重试");
                    int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (n < 0) throw new HttpError(400, "文件上传未完成");
                    out.write(buffer, 0, n); remaining -= n;
                }
            } finally { out.close(); }
            update(job, "queued", null);
            JobTask task = new JobTask(job, input, mime, session);
            if (!active(session)) throw new HttpError(503, "服务已停止，请重试");
            try { jobWorkers.execute(task); }
            catch (RejectedExecutionException stopped) { throw new HttpError(503, "服务已停止，请重试"); }
            queued = true;
            reply(socket, 202, "application/json; charset=utf-8", "{\"id\":" + job.id + ",\"state\":\"queued\"}");
        } catch (HttpError e) {
            if (job != null && !queued) update(job, "failed", e.getMessage());
            try { reply(socket, e.code, "application/json; charset=utf-8", error(e.getMessage())); } catch (IOException ignored) { }
        } catch (Exception e) {
            Log.w(TAG, "HTTP request failed", e);
            if (job != null && !queued) update(job, "failed", "上传失败，请重新选择文件后重试");
            try { reply(socket, 500, "application/json; charset=utf-8", error("上传失败，请稍后重试")); } catch (IOException ignored) { }
        } finally {
            if (!queued) { remove(input); if (reserved) capacity.release(); }
            sockets.remove(socket); close(socket);
        }
    }
    private final class JobTask implements Runnable {
        final Job job;
        final File input;
        final String mime;
        final long session;
        JobTask(Job job, File input, String mime, long session) { this.job = job; this.input = input; this.mime = mime; this.session = session; }
        @Override public void run() {
            File prn = null;
            try {
                if (!active(session) || Thread.currentThread().isInterrupted()) throw new IOException("stopped");
                update(job, "processing", null);
                prn = renderer.render(input, mime, job.copies, job.pages);
                if (prn == null || !prn.isFile() || prn.length() == 0) throw new IOException("Renderer returned no data");
                if (!active(session) || Thread.currentThread().isInterrupted()) throw new IOException("stopped");
                sender.send(prn);
                update(job, "sent", null);
            } catch (OutOfMemoryError e) {
                Log.w(TAG, "Print job memory limit", e);
                update(job, "failed", "魔盒内存不足，请减少页数或降低图片尺寸后重试");
            } catch (Exception e) {
                Log.w(TAG, "Print job " + job.id + " failed", e);
                // Never auto-resubmit after errors: some bytes could already have reached USB.
                update(job, "failed", active(session) ? publicFailure(e) : "服务已停止，任务未完成");
            } finally { remove(prn); remove(input); capacity.release(); }
        }
        void cancel() { update(job, "failed", "服务已停止，任务未处理"); remove(input); capacity.release(); }
    }
    private static String publicFailure(Exception e) {
        String fallback = "处理或发送失败，请检查文件和打印机后重试";
        String message = e.getMessage();
        if (!(e instanceof IOException) || message == null) return fallback;
        message = message.trim();
        if (message.length() == 0 || message.charAt(0) < '\u3400' || message.charAt(0) > '\u9fff'
                || message.indexOf('/') >= 0 || message.indexOf('\\') >= 0) return fallback;
        message = message.replaceAll("[\\p{Cntrl}]", " ");
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
    private static final class Job {
        final long id, created;
        final String name, pages;
        final int copies;
        String state = "uploading", failure;
        Job(long id, String name, int copies, String pages) { this.id = id; this.name = name; this.copies = copies; this.pages = pages; created = System.currentTimeMillis(); }
    }
    private synchronized void add(Job job) { recent.add(job); while (recent.size() > 20) recent.remove(0); }
    private synchronized void update(Job job, String state, String failure) { job.state = state; job.failure = failure; }
    private synchronized String jobList() {
        StringBuilder b = new StringBuilder("{\"jobs\":[");
        for (int i = recent.size() - 1; i >= 0; i--) {
            Job j = recent.get(i);
            if (i != recent.size() - 1) b.append(',');
            b.append("{\"id\":").append(j.id).append(",\"name\":").append(json(j.name))
                .append(",\"copies\":").append(j.copies).append(",\"pages\":").append(json(j.pages)).append(",\"created\":").append(j.created)
                .append(",\"state\":").append(json(j.state)).append(",\"error\":").append(j.failure == null ? "null" : json(j.failure)).append('}');
        }
        return b.append("]}").toString();
    }
    private String serviceStatus() {
        String status = running ? "服务已开启" : "服务已停止";
        try { if (statusProvider != null) status = statusProvider.get(); }
        catch (RuntimeException ignored) { }
        return "{\"running\":" + running + ",\"status\":" + json(status) + ",\"pending\":" + (5 - capacity.availablePermits()) + ",\"limit\":5}";
    }
    private static final class Request {
        String method, target;
        final Map<String, String> headers = new HashMap<String, String>();
    }
    private static Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int state = 0, previous = -1;
        long deadline = System.nanoTime() + 15000000000L;
        while (state != 4) {
            if (System.nanoTime() > deadline) throw new HttpError(408, "请求超时");
            int x = in.read();
            if (x < 0) throw new HttpError(400, "请求不完整");
            if ((x == 10 && previous != 13) || (previous == 13 && x != 10)) throw new HttpError(400, "无效请求头换行");
            previous = x;
            if ((x < 32 && x != 13 && x != 10 && x != 9) || x > 126) throw new HttpError(400, "无效请求头");
            header.write(x);
            if (header.size() > 16384) throw new HttpError(431, "请求头过大");
            if ((state == 0 || state == 2) && x == 13) state++;
            else if ((state == 1 || state == 3) && x == 10) state++;
            else state = x == 13 ? 1 : 0;
        }
        String[] lines = new String(header.toByteArray(), "US-ASCII").split("\r\n", -1);
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !(first[2].equals("HTTP/1.1") || first[2].equals("HTTP/1.0"))
                || !first[1].startsWith("/") || first[1].startsWith("//") || first[1].indexOf('#') >= 0) throw new HttpError(400, "无效请求");
        Request request = new Request(); request.method = first[0]; request.target = first[1];
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].length() == 0) continue;
            int colon = lines[i].indexOf(':');
            if (colon <= 0) throw new HttpError(400, "无效请求头");
            String name = lines[i].substring(0, colon).toLowerCase(Locale.US);
            if (!name.matches("[a-z0-9!#$%&'*+.^_`|~-]+") || request.headers.containsKey(name)) throw new HttpError(400, "重复或无效请求头");
            request.headers.put(name, lines[i].substring(colon + 1).trim());
        }
        return request;
    }
    private static boolean validHost(String host, InetAddress local) {
        if (host == null) return false;
        try {
            URI uri = new URI("http://" + host);
            if (uri.getUserInfo() != null || uri.getPort() != PORT || (uri.getRawPath() != null && uri.getRawPath().length() > 0)
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            String h = uri.getHost();
            if (h == null) return false;
            h = h.replace("[", "").replace("]", "");
            // Numeric hosts only: never resolve an attacker-controlled DNS name.
            if (!(h.matches("[0-9.]+") || h.indexOf(':') >= 0)) return false;
            InetAddress parsed = InetAddress.getByName(h.replace("%25", "%"));
            return Arrays.equals(parsed.getAddress(), local.getAddress());
        } catch (Exception invalid) { return false; }
    }
    private static int contentLength(String value) throws HttpError {
        if (value == null) throw new HttpError(411, "上传必须提供文件长度");
        if (!value.matches("[0-9]{1,10}")) throw new HttpError(400, "无效文件长度");
        long n;
        try { n = Long.parseLong(value); } catch (NumberFormatException e) { throw new HttpError(400, "无效文件长度"); }
        if (n > MAX_UPLOAD) throw new HttpError(413, "文件不能超过 20MB");
        if (n < 1) throw new HttpError(400, "文件为空");
        return (int)n;
    }
    private static final class Options {
        int copies = 1;
        String pages = "";
    }
    private static Options options(String query) throws HttpError {
        Options options = new Options();
        if (query.length() == 0) return options;
        Set<String> seen = new HashSet<String>();
        for (String pair : query.split("&", -1)) {
            int equal = pair.indexOf('=');
            if (equal <= 0) throw new HttpError(400, "打印参数无效");
            String key = pair.substring(0, equal);
            if (!(key.equals("copies") || key.equals("pages")) || !seen.add(key))
                throw new HttpError(400, "重复或不支持的打印参数");
            String value;
            try { value = URLDecoder.decode(pair.substring(equal + 1), "UTF-8"); }
            catch (Exception invalid) { throw new HttpError(400, "打印参数编码无效"); }
            if (key.equals("copies")) {
                if (!value.matches("(?:[1-9]|10)")) throw new HttpError(400, "份数需为 1–10");
                options.copies = Integer.parseInt(value);
            } else {
                if (value.length() > 200 || !value.matches("[0-9,， -]*"))
                    throw new HttpError(400, "页码格式无效，请填写例如 1,3-5，或留空打印全部");
                options.pages = value.trim();
            }
        }
        return options;
    }
    private static String fileName(String raw, String mime) throws HttpError {
        String name;
        try { name = raw == null ? "打印文件" : URLDecoder.decode(raw, "UTF-8"); }
        catch (Exception invalid) { throw new HttpError(400, "文件名无效"); }
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.length() == 0) name = "打印文件";
        return name.length() > 120 ? name.substring(0, 120) : name;
    }
    static String json(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') b.append('\\').append(c);
            else if (c < 32) b.append(String.format(Locale.US, "\\u%04x", (int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private static String error(String message) { return "{\"error\":" + json(message) + "}"; }
    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null) return false;
        int mismatch = a.length() ^ b.length();
        for (int i = 0; i < a.length(); i++) mismatch |= a.charAt(i) ^ (i < b.length() ? b.charAt(i) : 0);
        return mismatch == 0;
    }
    private static String makeToken() {
        byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes);
        StringBuilder b = new StringBuilder();
        for (byte x : bytes) b.append(String.format(Locale.US, "%02x", x & 255));
        return b.toString();
    }
    private static void reply(Socket socket, int code, String mime, String text) throws IOException {
        byte[] body = text.getBytes("UTF-8");
        String reason = code == 200 ? "OK" : code == 202 ? "Accepted" : "Error";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + mime
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\r\n\r\n";
        OutputStream out = socket.getOutputStream(); out.write(headers.getBytes("US-ASCII")); out.write(body); out.flush();
    }
    private static void close(Socket s) { try { s.close(); } catch (IOException ignored) { } }
    private static void remove(File f) { if (f != null && f.exists() && !f.delete()) Log.w(TAG, "Could not delete temporary job file"); }
    private static final class HttpError extends IOException {
        final int code;
        HttpError(int code, String message) { super(message); this.code = code; }
    }
}

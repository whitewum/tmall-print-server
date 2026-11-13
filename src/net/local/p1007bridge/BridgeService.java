// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class BridgeService extends Service {
    private static final String TAG = "P1007Bridge";
    static final String LOAD_FIRMWARE = "net.local.p1007bridge.LOAD_FIRMWARE";
    private static final AtomicLong generation = new AtomicLong();
    static volatile String status = "已停止";
    private long myGeneration;
    private volatile boolean running, firmwareLoading;
    private volatile ServerSocket listener;
    private volatile Socket client;
    private PrinterUsb printer; // owned exclusively by worker
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock serverWakeLock;
    private Discovery discovery;
    private WebPrintServer web;
    static volatile String webStatus = "网页服务尚未开启";
    private final AtomicBoolean manualFirmware = new AtomicBoolean();
    private boolean firmwareAttempted; // reset on physical unplug, not per job
    private volatile boolean resetFirmwareAttempt;
    private volatile boolean inspectPrinter = true;
    private volatile int latestStartId;

    static boolean enabled(Context c) {
        return c.getSharedPreferences("bridge", 0).getBoolean("enabled", false);
    }
    static void setEnabled(Context c, boolean value) {
        c.getSharedPreferences("bridge", 0).edit().putBoolean("enabled", value).commit();
    }
    static boolean isPrivate(InetAddress ip) {
        if (ip.isLoopbackAddress()) return true;
        byte[] a = ip.getAddress();
        if (a.length == 16) return ip.isLinkLocalAddress() || ((a[0] & 0xfe) == 0xfc);
        if (a.length != 4) return false;
        int first = a[0] & 255, second = a[1] & 255;
        return first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }
    static boolean hasFirmware(Context context) {
        try {
            InputStream in = context.getAssets().open("sihpP1005.dl");
            in.close(); return true;
        } catch (IOException absent) { return false; }
    }
    private void report(String message) {
        Log.i(TAG, message);
        if (generation.get() == myGeneration) status = message;
    }

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d == null || d.getVendorId() != PrinterUsb.VID || d.getProductId() != PrinterUsb.PID) return;
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                if (!firmwareLoading) {
                    resetFirmwareAttempt = true;
                    closeClient();
                    report("打印机已断开，等待重新连接");
                }
            } else { inspectPrinter = true; report("打印机已连接，等待打印"); }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        myGeneration = generation.incrementAndGet();
        discovery = new Discovery(this);
        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(usbEvents, f);
    }
    @Override public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (!enabled(this)) { stopSelf(startId); return START_NOT_STICKY; }
        if (intent != null && LOAD_FIRMWARE.equals(intent.getAction())) manualFirmware.set(true);
        inspectPrinter = true;
        if (running) return START_STICKY;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("P1007 网络打印")
                .setContentText("打印服务正在运行")
                .setContentIntent(open).setOngoing(true).build();
        startForeground(1, n);
        running = true;
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        serverWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "P1007Bridge:server");
        // Keep this plugged-in TV box out of idle standby while the print service is enabled.
        // Stopping the service releases this lock; no protected system settings are changed.
        serverWakeLock.acquire();
        discovery.start();
        try {
            web = new WebPrintServer(this, new DocumentRenderer(this), new RawPrintSender());
            web.setStatusProvider(new WebPrintServer.Status() {
                @Override public String get() {
                    UsbManager manager = (UsbManager)getSystemService(USB_SERVICE);
                    UsbDevice d = PrinterUsb.find(manager);
                    if (d == null) return "请连接并打开打印机";
                    if (!manager.hasPermission(d)) return "请在魔盒上允许访问打印机";
                    if (status.startsWith("已发送打印任务") || status.equals("服务已开启，等待打印")) return "打印机已连接，可以提交文件";
                    return status;
                }
            });
            web.start();
            webStatus = "网页服务已开启";
        } catch (IOException e) {
            webStatus = "网页服务启动失败";
            Log.e(TAG, webStatus, e);
        }
        new Thread(new Runnable() { @Override public void run() { serve(); } }, "p1007-server").start();
        return START_STICKY;
    }
    private void serve() {
        try {
            ServerSocket server = new ServerSocket();
            listener = server;
            server.setReuseAddress(true);
            server.setSoTimeout(1000);
            server.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 9100), 2);
            report("服务已开启，等待打印");
            while (running) {
                if (resetFirmwareAttempt) { firmwareAttempted = false; resetFirmwareAttempt = false; }
                if (inspectPrinter) {
                    inspectPrinter = false;
                    UsbManager manager = (UsbManager)getSystemService(USB_SERVICE);
                    UsbDevice attached = PrinterUsb.find(manager);
                    if (attached != null && manager.hasPermission(attached)) {
                        try {
                            acquireWakeLock();
                            printer = new PrinterUsb(manager);
                            ensureFirmware(false);
                            report("打印机已连接，等待打印");
                        } catch (Exception e) { report("打印机初始化未完成：" + e.getMessage()); }
                        finally { closePrinter(); releaseWakeLock(); }
                    }
                }
                if (manualFirmware.getAndSet(false)) {
                    try {
                        acquireWakeLock();
                        printer = new PrinterUsb((UsbManager)getSystemService(USB_SERVICE));
                        ensureFirmware(true);
                        report("手动固件加载完成，等待打印");
                    } catch (Exception e) { report("固件加载失败：" + e.getMessage()); }
                    finally { closePrinter(); releaseWakeLock(); }
                }
                if (!running) break;
                Socket accepted;
                try { accepted = server.accept(); }
                catch (SocketTimeoutException poll) { continue; }
                client = accepted;
                try {
                    if (!running || !isPrivate(accepted.getInetAddress())) {
                        Log.w(TAG, "Rejected non-local client: " + accepted.getInetAddress());
                        continue;
                    }
                    accepted.setSoTimeout(30000);
                    accepted.setTcpNoDelay(true);
                    transfer(accepted);
                    if (accepted.getInetAddress().isLoopbackAddress()) {
                        accepted.getOutputStream().write("OK\n".getBytes("US-ASCII"));
                        accepted.getOutputStream().flush();
                    }
                } catch (Exception e) {
                    if (running) report("打印中断：" + e.getMessage() + "。请检查打印机后重试");
                    if (accepted.getInetAddress().isLoopbackAddress()) try {
                        accepted.getOutputStream().write("ERROR\n".getBytes("US-ASCII"));
                        accepted.getOutputStream().flush();
                    } catch (IOException ignored) { }
                } finally { closeClient(); closePrinter(); releaseWakeLock(); }
            }
        } catch (IOException e) {
            if (running) report("服务未能启动：" + e.getMessage());
        } finally {
            boolean unexpected = running;
            running = false;
            closeListener(); closeClient(); closePrinter(); releaseWakeLock();
            // An old worker cannot stop a newer Service instance after rapid stop/start.
            if (unexpected && generation.get() == myGeneration) stopSelfResult(latestStartId);
        }
    }
    private void ensureFirmware(boolean manual) throws IOException {
        int state = printer.firmwareState();
        if (!manual && state == 1) return;
        if (!manual && (state == -1 || firmwareAttempted)) {
            report(state == -1 ? "无法确认打印机固件状态；如无出纸，可点“加载固件”" : "已尝试加载固件；如无出纸，可点“加载固件”");
            return;
        }
        if (!hasFirmware(this)) throw new IOException("缺少内置固件文件");
        firmwareLoading = true;
        firmwareAttempted = true;
        try {
            report("正在初始化打印机，请稍候");
            InputStream fw = getAssets().open("sihpP1005.dl");
            try {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = fw.read(chunk)) != -1) {
                    if (!running) throw new IOException("服务已停止");
                    printer.write(chunk, n);
                }
            } finally { fw.close(); }
            closePrinter();
            try { Thread.sleep(3000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("初始化中断"); }
            if (!running) throw new IOException("服务已停止");
            // Re-enumeration may revoke permission: report it, never silently grant it.
            long deadline = SystemClock.elapsedRealtime() + 15000;
            int loaded = -1;
            IOException last = null;
            do {
                try {
                    printer = new PrinterUsb((UsbManager)getSystemService(USB_SERVICE));
                    loaded = printer.firmwareState();
                    if (loaded == 1) break;
                    closePrinter();
                } catch (IOException e) { last = e; }
                try { Thread.sleep(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("初始化中断"); }
            } while (running && SystemClock.elapsedRealtime() < deadline);
            Log.i(TAG, "Firmware sent; verified post-load state=" + loaded);
            if (loaded != 1) throw new IOException("固件已发送，但打印机未确认就绪" + (last == null ? "" : ": " + last.getMessage()));
        } finally { firmwareLoading = false; }
    }
    private void transfer(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        byte[] buffer = new byte[16384];
        int n = input.read(buffer); // Empty probes never open or initialize USB.
        if (n < 0) return;
        if (!running) throw new IOException("服务已停止");
        acquireWakeLock();
        printer = new PrinterUsb((UsbManager)getSystemService(USB_SERVICE));
        ensureFirmware(false);
        long bytes = 0, started = SystemClock.elapsedRealtime();
        do {
            if (!running) throw new IOException("服务已停止");
            if (SystemClock.elapsedRealtime() - started > 600000 || bytes + n > 268435456L)
                throw new IOException("打印任务过大或超时");
            printer.write(buffer, n);
            bytes += n;
            n = input.read(buffer);
        } while (n != -1);
        Log.i(TAG, "Forwarded " + bytes + " bytes; paper output not verified");
        report("已发送打印任务（" + bytes + " 字节），等待下一次打印");
    }
    private synchronized void acquireWakeLock() {
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "P1007Bridge:job");
        wakeLock.acquire(660000);
    }
    private synchronized void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }
    private void closeClient() {
        Socket s = client; client = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
    private void closeListener() {
        ServerSocket s = listener; listener = null;
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
    private void closePrinter() {
        PrinterUsb p = printer; printer = null;
        if (p != null) p.close();
    }
    @Override public void onDestroy() {
        running = false;
        closeListener(); closeClient(); releaseWakeLock();
        discovery.stop();
        if (web != null) web.stop();
        webStatus = "网页服务已停止";
        if (serverWakeLock != null && serverWakeLock.isHeld()) serverWakeLock.release();
        unregisterReceiver(usbEvents);
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

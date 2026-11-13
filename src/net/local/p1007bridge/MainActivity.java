// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.net.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final String USB_PERMISSION = "net.local.p1007bridge.USB_PERMISSION";
    static final String REQUEST_USB = "net.local.p1007bridge.REQUEST_USB";
    private TextView info;
    private UsbManager usb;
    private boolean pendingManual;
    private final Handler handler = new Handler();
    private final Runnable refresh = new Runnable() {
        @Override public void run() { showState(); handler.postDelayed(this, 1000); }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice d = PrinterUsb.find(usb);
                if (d != null && usb.hasPermission(d)) startBridge(pendingManual);
                else BridgeService.status = "尚未允许访问打印机，请点“开始打印服务”重试";
                showState();
            }
        }
    };
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        usb = (UsbManager)getSystemService(USB_SERVICE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 20, 24, 20);
        info = new TextView(this);
        info.setTextSize(18);
        layout.addView(info);
        Button start = new Button(this);
        start.setText("开始打印服务");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { authorizeAndStart(false); }
        });
        layout.addView(start);
        Button stop = new Button(this);
        stop.setText("停止打印服务");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BridgeService.setEnabled(MainActivity.this, false);
                stopService(new Intent(MainActivity.this, BridgeService.class));
                BridgeService.status = "已停止，下次开机也不会自动启动";
                showState();
            }
        });
        layout.addView(stop);
        Button load = new Button(this);
        load.setText("加载打印机固件");
        load.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage("仅在打印机开机后没有出纸、自动初始化未成功时使用。现在加载？")
                    .setPositiveButton("加载", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) { authorizeAndStart(true); }
                    }).setNegativeButton("取消", null).show();
            }
        });
        layout.addView(load);
        TextView note = new TextView(this);
        note.setText("请保持魔盒连接家庭网络，打印机通过 USB 连接魔盒。\n"
                + "开启后会尝试在开机时恢复；USB 重新接入可能需要授权。\n"
                + "手机或平板可用浏览器打开网页地址，上传 PDF 或图片打印。\n"
                + "固件只加载到打印机内存，不会刷写魔盒系统。");
        layout.addView(note);
        setContentView(layout);
        registerReceiver(receiver, new IntentFilter(USB_PERMISSION));
        if (REQUEST_USB.equals(getIntent().getAction())
                || (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction()) && BridgeService.enabled(this))) authorizeAndStart(false);
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (REQUEST_USB.equals(i.getAction())
                || (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction()) && BridgeService.enabled(this))) authorizeAndStart(false);
    }
    private void authorizeAndStart(boolean manual) {
        pendingManual = manual;
        UsbDevice device = PrinterUsb.find(usb);
        if (device == null) {
            if (manual) { BridgeService.status = "请先连接并打开 P1007 打印机"; showState(); }
            else startBridge(false);
        } else if (usb.hasPermission(device)) startBridge(manual);
        else {
            Intent request = new Intent(USB_PERMISSION).setPackage(getPackageName());
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, request,
                    PendingIntent.FLAG_UPDATE_CURRENT));
        }
    }
    private void startBridge(boolean manual) {
        BridgeService.setEnabled(this, true);
        Intent i = new Intent(this, BridgeService.class);
        if (manual) i.setAction(BridgeService.LOAD_FIRMWARE);
        startService(i);
    }
    private void showState() {
        UsbDevice d = PrinterUsb.find(usb);
        info.setText("P1007 网络打印\n\n网页地址：" + localAddresses() + "\n打印机："
                + (d == null ? "未连接" : "HP P1007 / " + (usb.hasPermission(d) ? "已授权" : "等待授权"))
                + "\n打印机固件：" + (BridgeService.hasFirmware(this) ? "已内置" : "缺少文件")
                + "\n网页服务：" + BridgeService.webStatus
                + "\n开机自动启动：" + (BridgeService.enabled(this) ? "已开启" : "已关闭")
                + "\n\n" + BridgeService.status + "\n");
    }
    private String localAddresses() {
        List<String> addresses = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) while (interfaces.hasMoreElements()) {
                NetworkInterface n = interfaces.nextElement();
                Enumeration<InetAddress> a = n.getInetAddresses();
                while (a.hasMoreElements()) {
                    InetAddress ip = a.nextElement();
                    if (ip instanceof Inet4Address && BridgeService.isPrivate(ip)) addresses.add("http://" + ip.getHostAddress() + ":8080");
                }
            }
        } catch (Exception ignored) { }
        return addresses.isEmpty() ? "尚未连接网络" : addresses.toString();
    }
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override public void onDestroy() { unregisterReceiver(receiver); super.onDestroy(); }
}

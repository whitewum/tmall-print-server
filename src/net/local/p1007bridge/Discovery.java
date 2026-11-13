// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import android.content.*;
import android.net.ConnectivityManager;
import android.net.nsd.*;
import android.os.Handler;
import android.util.Log;
import java.net.*;
import java.util.*;

/** Publishes a RAW print service. Discovery is independent of the DHCP address. */
final class Discovery {
    private final Context context;
    private final NsdManager manager;
    private final Handler handler = new Handler();
    private Registration registration;
    private boolean active;
    private final Runnable register = new Runnable() {
        @Override public void run() { registerNow(); }
    };
    private final BroadcastReceiver network = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };

    Discovery(Context context) {
        this.context = context;
        manager = (NsdManager)context.getSystemService(Context.NSD_SERVICE);
    }
    void start() {
        if (active) return;
        active = true;
        context.registerReceiver(network, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        refresh();
    }
    void stop() {
        if (!active) return;
        active = false;
        handler.removeCallbacks(register);
        context.unregisterReceiver(network);
        unregister();
    }
    private void refresh() {
        if (!active) return;
        handler.removeCallbacks(register);
        unregister();
        handler.postDelayed(register, 1500);
    }
    private void unregister() {
        Registration previous = registration;
        registration = null;
        if (previous != null) try { manager.unregisterService(previous); }
        catch (RuntimeException e) { Log.w("P1007Bridge", "Discovery unregister: " + e); }
    }
    private void registerNow() {
        if (!active || registration != null) return;
        if (!hasLanAddress()) { handler.postDelayed(register, 5000); return; }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("HP P1007 Tmall");
        info.setServiceType("_pdl-datastream._tcp.");
        info.setPort(9100);
        info.setAttribute("txtvers", "1");
        info.setAttribute("qtotal", "1");
        info.setAttribute("ty", "HP LaserJet P1007");
        info.setAttribute("product", "(HP LaserJet P1007)");
        info.setAttribute("usb_MFG", "HP");
        info.setAttribute("usb_MDL", "HP LaserJet P1007");
        info.setAttribute("pdl", "application/octet-stream");
        Registration next = new Registration();
        registration = next;
        try { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, next); }
        catch (RuntimeException e) {
            registration = null;
            Log.w("P1007Bridge", "Discovery register: " + e);
            handler.postDelayed(register, 5000);
        }
    }
    private boolean hasLanAddress() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface n = all.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> addresses = n.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && BridgeService.isPrivate(address)) return true;
                }
            }
        } catch (SocketException e) { Log.w("P1007Bridge", "Discovery interfaces: " + e); }
        return false;
    }
    private final class Registration implements NsdManager.RegistrationListener {
        @Override public void onServiceRegistered(NsdServiceInfo info) {
            Log.i("P1007Bridge", "Discovery registered: " + info.getServiceName());
        }
        @Override public void onRegistrationFailed(NsdServiceInfo info, int error) {
            Log.w("P1007Bridge", "Discovery registration failed: " + error);
            handler.post(new Runnable() {
                @Override public void run() {
                    if (registration == Registration.this) {
                        registration = null;
                        if (active) handler.postDelayed(register, 5000);
                    }
                }
            });
        }
        @Override public void onServiceUnregistered(NsdServiceInfo info) { }
        @Override public void onUnregistrationFailed(NsdServiceInfo info, int error) {
            Log.w("P1007Bridge", "Discovery unregistration failed: " + error);
        }
    }
}

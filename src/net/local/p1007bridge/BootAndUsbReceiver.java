// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import android.content.*;
import android.hardware.usb.*;

public final class BootAndUsbReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String action = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) return;
        if (!BridgeService.enabled(c)) return;
        UsbManager usb = (UsbManager)c.getSystemService(Context.USB_SERVICE);
        UsbDevice device = PrinterUsb.find(usb);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            UsbDevice attached = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (attached == null || attached.getVendorId() != PrinterUsb.VID
                    || attached.getProductId() != PrinterUsb.PID || device == null) return;
        }
        c.startService(new Intent(c, BridgeService.class));
        if (device != null && !usb.hasPermission(device)) {
            c.startActivity(new Intent(c, MainActivity.class)
                    .setAction(MainActivity.REQUEST_USB).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}

// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import android.hardware.usb.*;
import java.io.IOException;

/** USB operations are called only by the server worker, never by the UI. */
final class PrinterUsb {
    static final int VID = 0x03f0, PID = 0x4817;
    final UsbDeviceConnection connection;
    private final UsbInterface intf;
    private final UsbEndpoint out;

    static UsbDevice find(UsbManager manager) {
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    PrinterUsb(UsbManager manager) throws IOException {
        UsbDevice device = find(manager);
        if (device == null) throw new IOException("P1007 not attached");
        if (!manager.hasPermission(device)) throw new IOException("USB permission required; reopen app");
        UsbInterface foundIntf = null;
        UsbEndpoint foundOut = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_PRINTER) continue;
            for (int j = 0; j < candidate.getEndpointCount(); j++) {
                UsbEndpoint ep = candidate.getEndpoint(j);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    foundIntf = candidate;
                    foundOut = ep;
                    break;
                }
            }
            if (foundOut != null) break;
        }
        if (foundOut == null) throw new IOException("No USB printer bulk OUT endpoint");
        UsbDeviceConnection c = manager.openDevice(device);
        if (c == null) throw new IOException("Cannot open USB printer");
        if (!c.claimInterface(foundIntf, true)) {
            c.close();
            throw new IOException("Cannot claim USB printer interface");
        }
        connection = c;
        intf = foundIntf;
        out = foundOut;
    }

    void write(byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int n = connection.bulkTransfer(out, buffer, offset,
                    Math.min(16384, length - offset), 5000);
            if (n <= 0) throw new IOException("USB bulk OUT failed (" + n + ")");
            offset += n;
        }
    }

    /** 1 = FWVER present, 0 = a complete valid ID confirms it is absent, -1 = unknown. */
    int firmwareState() {
        byte[] data = new byte[1024];
        int n = connection.controlTransfer(0xa1, 0, 0, intf.getId(), data, data.length, 2000);
        if (n < 3) { android.util.Log.i("P1007Bridge", "Printer ID query length=" + n); return -1; }
        int length = ((data[0] & 255) << 8) | (data[1] & 255);
        if (length < 3 || length > n) { android.util.Log.i("P1007Bridge", "Printer ID length mismatch: declared=" + length + ", received=" + n); return -1; }
        try {
            String id = new String(data, 2, length - 2, "US-ASCII").replace("\u0000", "");
            android.util.Log.i("P1007Bridge", "Printer ID: " + id);
            if (!(id.contains("MFG:") || id.contains("MANUFACTURER:"))
                    || !(id.contains("MDL:") || id.contains("MODEL:"))) return -1;
            for (String field : id.split(";")) {
                if (field.trim().toUpperCase(java.util.Locale.US).startsWith("FWVER:")) {
                    String value = field.substring(field.indexOf(':') + 1).trim();
                    return value.length() > 0 && !value.matches("0+") ? 1 : 0;
                }
            }
            return 0;
        } catch (java.io.UnsupportedEncodingException impossible) { return -1; }
    }

    void close() {
        try { connection.releaseInterface(intf); } catch (RuntimeException ignored) { }
        connection.close();
    }
}

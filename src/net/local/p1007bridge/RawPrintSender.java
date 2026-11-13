// SPDX-License-Identifier: GPL-2.0-or-later
package net.local.p1007bridge;

import java.io.*;
import java.net.*;

/** Uses the same serialized USB worker as computer print jobs. */
final class RawPrintSender implements WebPrintServer.Sender {
    @Override public void send(File file) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 9100), 5000);
            socket.setSoTimeout(120000);
            try (InputStream input = new FileInputStream(file)) {
                OutputStream out = socket.getOutputStream();
                byte[] buffer = new byte[16384]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("打印服务已停止");
                    out.write(buffer, 0, n);
                }
                out.flush();
            }
            socket.shutdownOutput();
            InputStream reply = socket.getInputStream();
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            for (int i = 0; i < 32; i++) {
                int ch = reply.read();
                if (ch < 0 || ch == '\n') break;
                message.write(ch);
            }
            if (!"OK".equals(message.toString("US-ASCII")))
                throw new IOException("打印机未确认接收，请检查电源和 USB 连接；请勿重复提交已有出纸的任务");
        }
    }
}

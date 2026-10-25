package com.tv.neighbor;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * SMB 协议探测：TCP 445 连上后发一个 SMB2 NEGOTIATE 探针，
 * 依据响应头判定对方是不是 SMB 服务。用于过滤"445 开着但不是 SMB"的设备。
 * 探针不带协商上下文，只能确认"是 SMB2/3 服务"，不能测出真实最高方言。
 */
final class SmbProbe {
    private SmbProbe() {}

    /** @return null = 不是 SMB；"SMB1" / "SMB2/3" = 探测成功 */
    static String probe(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, 445), 800);
            s.setSoTimeout(1500);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            // SMB2 NEGOTIATE：请求 dialect 2.0.2/2.1/3.0/3.0.2
            // 方言数组在 body 偏移 40（MS-SMB2 2.2.3 固定区 40 字节）
            byte[] dialects = {0x02, 0x02, 0x02, 0x10, 0x03, 0x00, 0x03, 0x02};
            int bodyLen = 40 + dialects.length;
            int msgLen = 64 + bodyLen;
            byte[] pkt = new byte[4 + msgLen];
            pkt[3] = (byte) msgLen; // NetBIOS 会话头（长度 < 16MB）
            // SMB2 header
            pkt[4] = (byte) 0xFE; pkt[5] = 'S'; pkt[6] = 'M'; pkt[7] = 'B';
            pkt[8] = 0x40; pkt[9] = 0x00;              // StructureSize=64
            // MessageId=0, SessionId=0, TreeId=0 全零即可
            // body
            int o = 4 + 64;
            pkt[o] = 0x24; pkt[o + 1] = 0x00;          // StructureSize=36
            pkt[o + 2] = 0x04; pkt[o + 3] = 0x00;      // DialectCount=4
            pkt[o + 4] = 0x01; pkt[o + 5] = 0x00;      // SecurityMode = signing enabled
            System.arraycopy(dialects, 0, pkt, o + 40, dialects.length);
            out.write(pkt);
            out.flush();

            // 响应: 4 字节 NetBIOS 长度 + SMB 头；协议标识在偏移 4..7
            byte[] hdr = readFully(in, 8);
            if (hdr == null) return null;
            if (hdr[4] == (byte) 0xFF && hdr[5] == 'S' && hdr[6] == 'M' && hdr[7] == 'B') {
                return "SMB1";
            }
            if (hdr[4] == (byte) 0xFE && hdr[5] == 'S' && hdr[6] == 'M' && hdr[7] == 'B') {
                return "SMB2/3";
            }
            return null; // 有响应但不是 SMB
        } catch (Exception e) {
            return null;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readFully(InputStream in, int n) {
        byte[] b = new byte[n];
        int got = 0;
        try {
            while (got < n) {
                int r = in.read(b, got, n - got);
                if (r < 0) break;
                got += r;
            }
        } catch (Exception e) {
            return got >= n ? b : null;
        }
        return got >= n ? b : null;
    }
}

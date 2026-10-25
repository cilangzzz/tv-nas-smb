package com.tv.neighbor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * 极简 NBNS（NetBIOS Name Service，UDP 137）Node Status 查询。
 * Windows/NAS 即使关了 SMB1，NetBT 驱动通常仍会响应 Node Status，
 * 用于把 IP 解析成机器名；失败就退回用 IP 当名字。
 */
final class Nbns {
    private Nbns() {}

    /** 查询主机名，1.2 秒超时 ×2 次，失败返回 null。 */
    static String query(String ip) {
        byte[] name = encName("*");
        for (int attempt = 0; attempt < 2; attempt++) {
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                s.setSoTimeout(1200);
                byte[] q = buildQuery(name, (short) (0x8000 + attempt));
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), 137));
                byte[] buf = new byte[1024];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String n = parseName(p.getData(), p.getLength());
                if (n != null) return n;
            } catch (SocketTimeoutException e) {
                // retry / give up
            } catch (Exception e) {
                return null;
            } finally {
                if (s != null) s.close();
            }
        }
        return null;
    }

    /** 第一级编码：每字节拆成两个 4bit + 'A'。name 长度最多 15。 */
    private static byte[] encName(String raw) {
        String n = raw;
        if (n.length() > 15) n = n.substring(0, 15);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            sb.append((char) ('A' + ((c >> 4) & 0xF)));
            sb.append((char) ('A' + (c & 0xF)));
        }
        for (int i = n.length(); i < 15; i++) sb.append("CACACACACACACACA");
        sb.append("CA"); // 补满 32 字符（15 字节 + 0x00 填充字节）
        String enc = sb.toString();
        enc = enc.substring(0, 32);
        byte[] out = new byte[1 + 32 + 1];
        out[0] = 32;
        for (int i = 0; i < 32; i++) out[1 + i] = (byte) enc.charAt(i);
        out[33] = 0;
        return out;
    }

    /** 组 Node Status Request 包。 */
    private static byte[] buildQuery(byte[] encQname, short tid) {
        byte[] suffix = {0, 32 /*NB*/, 0, 1 /*IN*/, 0, 0, 0, 0 /*TTL*/, 0, 0 /*rdlen*/};
        byte[] pkt = new byte[12 + encQname.length + suffix.length];
        pkt[0] = (byte) (tid >> 8);
        pkt[1] = (byte) tid;
        pkt[2] = 0x00; // flags: unicast opcode 0
        pkt[3] = 0x10; // recursion desired
        pkt[4] = 0; pkt[5] = 1; // QDCOUNT=1
        int o = 12;
        System.arraycopy(encQname, 0, pkt, o, encQname.length);
        o += encQname.length;
        System.arraycopy(suffix, 0, pkt, o, suffix.length);
        return pkt;
    }

    /** 解析响应里的第一个唯一工作站名（suffix 0x00、非组名）。 */
    private static String parseName(byte[] d, int len) {
        try {
            int an = ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
            if (an == 0) return null;
            // 跳过问题区：name（len 字节 + 数据 + 0）
            int o = 12;
            int qlen = d[o] & 0xFF;
            o += 1 + qlen + 1;
            // 跳过 QTYPE/QCLASS
            o += 4;
            // 答案 RR：压缩 name（指针 2 字节）
            o += 2;
            int type = ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
            o += 2; // type = 0x21 NBSTAT
            o += 2; // class
            o += 4; // TTL
            int rdlen = ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
            o += 2;
            int rrend = Math.min(o + rdlen, len);
            if (type != 0x21 || o + 1 > rrend) return null;
            int num = d[o] & 0xFF;
            o += 1;
            String fallback = null;
            for (int i = 0; i < num && o + 18 <= rrend; i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < 15; j++) {
                    char c = (char) (d[o + j] & 0xFF);
                    if (c == 0) break;
                    sb.append(c);
                }
                String nm = sb.toString().trim();
                int suffix = d[o + 15] & 0xFF;
                int flags = ((d[o + 16] & 0xFF) << 8) | (d[o + 17] & 0xFF);
                o += 18;
                boolean group = (flags & 0x8000) != 0;
                if (nm.length() > 0 && suffix == 0x00 && !group) return nm;
                if (fallback == null && nm.length() > 0 && !group) fallback = nm;
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}

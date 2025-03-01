package qidizi.tv;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class TvIp {
    protected static String get() throws Exception {
        String ip = null;
        Enumeration<NetworkInterface> en;

        try {
            en = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            Util.debugException(e, "获取本设备网络接口时");
            throw new Exception("无法获得网络接口: " + Util.getExceptionMessage(e));
        }

        while (en.hasMoreElements()) {
            NetworkInterface nif = en.nextElement();
            for (Enumeration<InetAddress> enumIps = nif.getInetAddresses(); enumIps.hasMoreElements(); ) {
                InetAddress inetAddress = enumIps.nextElement();
                if (inetAddress.isLoopbackAddress() || !(inetAddress instanceof Inet4Address))
                    continue;
                String address = inetAddress.getHostAddress();
                if (null == address)
                    break;

                if (address.startsWith("10.") || address.startsWith("192.168.")) {
                    ip = address;
                    break;
                }

                if (address.startsWith("172.")) {
                    int sub = Integer.parseInt(address.split("\\.")[1]);

                    if (sub >= 16 && sub <= 31) {
                        ip = address;
                        break;
                    }
                }
            }

            if (ip != null) {
                break;
            }
        }

        if (null == ip)
            throw new Exception("无法推算出本设备的内网ip");

        return ip;
    }
}

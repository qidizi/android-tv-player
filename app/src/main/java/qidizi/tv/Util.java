package qidizi.tv;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.Locale;

public class Util {
    final protected static boolean debug = true;

    protected static void debugException(Exception e, String from) {
        Log.e("qDebug", "执行如下操作时报错了：" + from, e);
    }

    protected static String getExceptionMessage(Exception e) {
        String message = e.getMessage();
        Throwable cause = e.getCause();
        while (null != cause) {
            // socket的异常原因比较复杂，且层级比较多，最后的消息可能无法确认问题，需要整个链的错误信息
            //noinspection StringConcatenationInLoop
            message += "，原因：" + cause.getMessage();
            cause = cause.getCause();
        }
        return message;
    }

    /**
     * @noinspection SameParameterValue
     */
    protected static void consoleDebug(String format, Object... vars) {
        if (!debug) return;
        Log.e("qDebug", String.format(format, vars));
    }

    protected static String msHuman(long l) {
        l /= 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", l / 3600, l % 3600 / 60, l % 60);
    }

    protected static int getVerifyHttpProxyPort(String host, String port) throws Exception {
        if (null == host || host.isEmpty() || null == port || port.isEmpty()) {
            // 空不报错
            // -1 端口无效
            return -1;
        }

        // 非空且非法时要抛出异常，方便使用人能发觉
        if (!port.matches("^\\d{4,5}$")) {
            // 因为1024 是保留端口，一般不可能让用于代理服务
            throw new Exception("代理端口必须是4～5位数字： " + port);
        }

        int portInt = Integer.parseInt(port);

        if (portInt < 1025)
            throw new Exception("代理端口必须大于1024(保留端口): " + port);

        if (portInt > 65535)
            throw new Exception("代理端口必须小于65535: " + port);

        if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            // ipv4

            if (host.startsWith("0.")) {
                throw new Exception("ipv4代理主机地址的0地址不应使用： " + host);
            }

            String[] ip = host.split("\\.");
            for (String s : ip) {
                int sub = Integer.parseInt(s);

                if (sub == 255)
                    throw new Exception("ipv4代理主机的255地址不应使用： " + host);

                if (sub > 255)
                    throw new Exception("ipv4代理主机非法： " + host);

            }

            return Integer.parseInt(port);
        }

        if (host.matches("^[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{1,4}){7}$")) {
            // ipv6,不考虑缩写模式

            //noinspection SpellCheckingInspection
            if (host.toLowerCase(Locale.US).startsWith("ffff:")) {
                throw new Exception("不应使用的ipv6代理主机地址： " + host);
            }

            return Integer.parseInt(port);
        }

        if (host.matches("^([a-zA-Z\\d]([a-zA-Z\\d-]{1,61})?\\.)+[a-zA-Z]{2,20}$")) {
            // 域名
            return Integer.parseInt(port);
        }

        throw new Exception("代理主机只能是ipv4、ipv6、域名： " + host);
    }

    protected static int str2int(String v) {
        // 不需要抛出异常的转换
        if (null == v || v.isEmpty() || !v.matches("^\\d+$")) return 0;
        return Integer.parseInt(v);
    }

    protected static String urlEncode(String v) {
        try {
            String code = "UTF-8";
            return URLEncoder.encode(v, code);
        } catch (Exception e) {
            return "url转义失败：" + Util.getExceptionMessage(e) + "。待转义文字： " + v;
        }
    }
    protected static String getIp() throws Exception {
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

package qidizi.tv;

import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

public class TvServer {
    private ServerSocket server;
    private final MainActivity activity;
    private final String byteCharset = "UTF-8";

    // ~/android/platform-tools/adb forward tcp:8000 tcp:8000 来影射avd中的端口到物理机
    // 一般的系统是不允许普通应用开启1024以下的端口的
    // activity 生命周期
    // https://developer.android.google.cn/guide/components/activities/activity-lifecycle?hl=zh-cn
    protected TvServer(MainActivity activity) {
        this.activity = activity;
        create();
    }

    private void create() {
        new Thread(() -> {
            // 同时只支持1个连接
            int connectQueue = 1;
            try (ServerSocket tryServer = new ServerSocket(StaticData.server_port, connectQueue)) {
                // 使用端口转发功能,把虚拟机avd端口转发到开发机的8080上,就可以使用 http://127.0.0.1:8080 来访问
                // ~/android/platform-tools/adb forward tcp:8080 tcp:8080
                // 同时只多个请求
                // try不支持给成员变量赋值，只能使用成员变量的set方法
                server = tryServer;
                server.setReuseAddress(true);

                if (!server.isBound()) {
                    throw new Exception("tv http服务无法绑定监听端口： " + StaticData.server_port);
                }

                int si = 0;
                activity.tvToast.push("tv http服务启动成功: " + StaticData.tvIp + ":" + StaticData.server_port);
                Util.consoleDebug("tv http服务启动成功: %s:%d", StaticData.tvIp, StaticData.server_port);
                // 要加入null判断，防止异常后，http变成null
                // noinspection
                while (null != server && !server.isClosed()) {
                    // 若有问题，使用类似下方命令来测试
                    /*

                     echo -e "\n\n";echo -n "[";echo -e "GET /?action=getInfo HTTP/1.1\r\n" | nc 10.10.10.3 8888 > _test.txt;od -c _test.txt;echo -e "\n\n\n"

                     */
                    try (
                            // 阻塞直到有新的客户端连接;不要设置server读的超时，否则不会block 而是 rwTimeoutMs 后就 Poll timed out
                            Socket clientSocket = server.accept();
                            InputStream is = clientSocket.getInputStream();
                            // 变量名用 ignoreAutoCloseOs 防止ide提示未使用
                            OutputStream os = clientSocket.getOutputStream()
                            // 关闭顺序为后到前
                    ) {
                        si++;

                        // 局域网不用太久
                        int rwTimeoutMs = 3000;
                        clientSocket.setSoTimeout(rwTimeoutMs);
                        // 经过测试
                        // 禁用 Nagle算法，立即发送数据，否则有很大概率os.write()只有首次调用发出去，socket就关闭，
                        // 后面全未发，另外开启后，可以自己合并内容一次write
                        // 具体见ai
                        // clientSocket.setTcpNoDelay(true);
                        // 最大支持1MB 的请求，应该够用了
                        // 缓冲与读取都要比最大支持大1位，方便下面判断是否超出
                        int maxGet = 1024 * 1024;
                        int overMax = maxGet + 1;
                        byte[] buffer = new byte[overMax];
                        int totalRead = 0;
                        int byteRead;
                        boolean foundRN = false;
                        // 注意只取 报文首行，如是 GET/HEAD/POST /?a=b HTTP/1.0\r\n;
                        // 再取/?a=b这段，后面内容全部不要
                        // 没有body的请求，如get/head是没有Content-Length，所以，无法判断收到的字符长度来结尾，而需要读到\r\n即可
                        // 自定义的server，qs这段其实可以无上限
                        while (totalRead < overMax && (byteRead = is.read()) != -1) {
                            // GET /?a=b HTTP/1.0\r\n 处理换行符
                            if ('\r' == byteRead || '\n' == byteRead) {
                                // 取的内容不要 \r\n
                                foundRN = true;
                                break;
                            }

                            buffer[totalRead++] = (byte) byteRead;
                        }

                        clientSocket.shutdownInput();

                        if (totalRead < 1) {
                            // 没内容是0
                            // 结尾了是-1;比如请求未结束，浏览器就刷新重新请求，浏览器就会终止上个请求，所以是-1
                            throw new IOException("出错了！你提交的请求http报文长度为:" + totalRead);
                        } else if (totalRead > maxGet) {
                            // 超过了最大长度
                            throw new IOException("出错了！请求时提交超过 " + maxGet + " bytes");
                        }

                        String str = getString(buffer, totalRead, foundRN);
                        Uri uri = Uri.parse(str);
                        final String action = uri.getQueryParameter("action");

                        if (null == action || action.isEmpty()) {
                            emptyAction(clientSocket);
                        } else {
                            actionRun(clientSocket, action, uri);
                        }

                        //debugSocket(clientSocket, "clientTry最后");
                    } catch (Exception e) {
                        Util.debugException(e, si + "-解析tv http请求时");
                        // 如果联网权限未得到，这里会报  （避免studio语法提示）
                        // android.system.ErrnoException: accept failed: E A C C E S (Permission denied)
                        activity.tvToast.push("tv http服务无法处理请求: " + Util.getExceptionMessage(e));
                    }
                }
                // 比如有异常，提示用户
                activity.tvToast.push("tv http服务已停止");
            } catch (Exception e) {
                Util.debugException(e, "创建tv http服务时");
                activity.tvToast.push("tv http服务异常：" + Util.getExceptionMessage(e));
            }
        }).start();
    }

    private String getString(byte[] buffer, int totalRead, boolean foundRN) throws IOException {
        String str = new String(buffer, 0, totalRead, byteCharset);

        if (!foundRN) {
            throw new IOException("未收到http报文首行结束符 \\r\\n !?");
        }

        // GET/HEAD/POST/... /?xx=yy HTTP/1.x
        if (!str.matches("^[A-Z]+ [^ ]+ HTTP/\\d+.+$")) {
            // sub超过实际报错而不是取实际
            throw new IOException("控制请求不是http协议：" + str.substring(0, Math.min(str.length(), 100)) + "...");
        }

        // GET/HEAD/POST /a/b/c.html?a=b HTTP/1.0
        str = str.split(" ", 3)[1];
        return str;
    }

    private void emptyAction(Socket clientSocket) throws Exception {
        // 不是action指令状态，无条件返回html内容
        try (
                InputStream htmlIs = activity.getResources().openRawResource(R.raw.index);
                ByteArrayOutputStream baOs = new ByteArrayOutputStream();
        ) {
            int bodyLen = 0;
            byte[] buffer = new byte[1024];
            int read;
            while ((read = htmlIs.read(buffer)) != -1) {
                baOs.write(buffer, 0, read);
                bodyLen += read;
            }

            if (bodyLen < 1) {
                throw new Exception("读取app内置的 index.html 文件内容长度错误，已读取： " + bodyLen + "bytes");
            }

            response(clientSocket, baOs.toByteArray(), bodyLen, "已响应控制页面", 200, "OK");
        }
    }

    private void actionRun(Socket client, String action, Uri uri) throws Exception {
        try {
            // 有时，电视与html提示内容可能不同
            String tip, text;
            switch (action) {
                case "playUrl":
                    String url = uri.getQueryParameter("videoUrl");
                    String httpProxyHost = uri.getQueryParameter("httpProxyHost");
                    String httpProxyPort = uri.getQueryParameter("httpProxyPort");
                    int port = Util.getVerifyHttpProxyPort(httpProxyHost, httpProxyPort);
                    text = tip = "正在尝试播放" + (port > 0 ? "(走代理) " : " ") + url;
                    activity.tvPlayer.playUrl(
                            url,
                            uri.getQueryParameter("userAgent"),
                            uri.getQueryParameter("refererUrl"),
                            httpProxyHost,
                            httpProxyPort
                    );
                    responseText(client, text, tip);
                    return;
                case "play":
                    activity.tvPlayer.play();
                    responseText(client, "正尝试播放", "请求播放");
                    return;
                case "pause":
                    activity.tvPlayer.pause();
                    responseText(client, "正尝试暂停", "请求暂停");
                    return;
                case "volumeCtrl":
                    tip = Volume.adjust(activity, uri.getQueryParameter("how"));
                    text = "尝试" + tip;
                    responseText(client, text, tip);
                    return;
                case "seek":
                    int ms = Util.str2int(uri.getQueryParameter("seekMinutes"));
                    activity.tvPlayer.seek(ms);
                    tip = "请求跳转到" + ms + "分钟";
                    text = "尝试跳转到" + ms + "分钟";
                    responseText(client, text, tip);
                    return;
                case "getInfo":
                    text = getInfo();
                    tip = "电视信息已返回";
                    responseQueryString(client, text, tip);
                    return;
                case "fetchOtherOrigin":
                    String remoteUrl = uri.getQueryParameter("url");
                    String method = uri.getQueryParameter("method");
                    String userAgent = uri.getQueryParameter("userAgent");
                    String referer = uri.getQueryParameter("referer");
                    String proxyHost = uri.getQueryParameter("httpProxyHost");
                    String proxyPort = uri.getQueryParameter("httpProxyPort");
                    String pageCharset = uri.getQueryParameter("pageCharset");
                    int proxyPortInt = Util.getVerifyHttpProxyPort(proxyHost, proxyPort);
                    tip = (proxyPortInt > 0 ? "走代理" : "") + "请求:" + remoteUrl;
                    text = HttpRequest.execute(
                            remoteUrl,
                            method,
                            userAgent,
                            referer,
                            proxyHost,
                            proxyPort,
                            pageCharset
                    );
                    responseText(client, text, tip);
                    return;
                default:
                    throw new Exception("不支持的控制请求：" + action);
            }
        } catch (Exception e) {
            Util.debugException(e, "处理tv http请求时: " + action);
            String msg = action + " 操作异常： " + Util.getExceptionMessage(e);
            responseFail(client, msg, msg);
        }
    }

    private String getInfo() {
        DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;
        float density = displayMetrics.density;
        int densityDpi = displayMetrics.densityDpi;
        String devName = Build.MODEL;
        // 用url encode 足够，处理也简单
        return String.format(Locale.US, "devName=%s&devInfo=%s&videoUrl=%s&durationMs=%d&positionMs=%d&playErrorCode=%d",
                Util.urlEncode(devName),
                Util.urlEncode(String.format(Locale.US, "%s %s density:%.2f densityDpi:%d (%dx%d) Android %s %s %s textSize:%.2fsp qrSize:%d\n\n欢迎使用%s！",
                        Build.BRAND, devName, density, densityDpi, screenWidth, screenHeight, Build.VERSION.RELEASE,
                        Build.MANUFACTURER, Build.PRODUCT, StaticData.baseTextSizeSp, StaticData.qrSize, activity.getString(R.string.app_name))),
                Util.urlEncode(StaticData.videoUrl),
                StaticData.durationMs,
                StaticData.positionMs,
                StaticData.playerErrorCode);
    }

    private void responseFail(Socket client, String text, String tip) throws Exception {
        response(client, text, tip, 500, "FAIL");
    }

    private void responseQueryString(Socket client, String text, String tip) throws Exception {
        response(client, text, tip, 200, "OK_queryString");
    }

    private void responseText(Socket client, String text, String tip) throws Exception {
        response(client, text, tip, 200, "OK");
    }

    private void response(Socket clientSocket, String text, String tip, int status, String statusText) throws Exception {
        if (null == text) throw new IOException("response text 不能为null");
        byte[] body = text.getBytes(byteCharset);
        response(clientSocket, body, body.length, tip, status, statusText);
    }

    private void response(Socket clientSocket, byte[] body, int bodyLength, String tip, int status, String statusText) throws
            Exception {
        if (clientSocket.isClosed() || clientSocket.isOutputShutdown()) {
            activity.tvToast.push(tip);
            debugSocket(clientSocket, "socket 关闭了，还调用 response: " + new String(body, 0, bodyLength, byteCharset));
            return;
        }

        if (status < 1) {
            status = 200;
            statusText = "OK";
        } else if (null == statusText || statusText.isEmpty()) {
            statusText = "OK";
        }

        statusText = status + " " + statusText.trim();
        OutputStream os = clientSocket.getOutputStream();

        // 允许跨域请求
        os.write(("HTTP/1.1 " + statusText + "\r\nServer: tvServer\r\nConnection: close\r\nContent-Type: text/html;charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nVary: Origin\r\nContent-Length: "
                + bodyLength + "\r\n\r\n").getBytes(byteCharset));
        os.flush();

        if (bodyLength > 0) {
            int send = 0;
            int maxBufferSize = clientSocket.getSendBufferSize();
            while (send < bodyLength) {
                int sendThisTime = Math.min(bodyLength - send, maxBufferSize);
                os.write(body, send, sendThisTime);
                send += sendThisTime;
                Thread.sleep(50);
                os.flush();
            }
        }

        if (!clientSocket.isOutputShutdown())
            clientSocket.shutdownOutput();
        activity.tvToast.push(tip);

        // todo 先暂停x，再关闭好像能解决缓冲内容发送未完就关闭socket的问题？
        //Thread.sleep(50);
    }

    /**
     * @noinspection SameParameterValue
     */
    private void debugSocket(Socket clientSocket, String title) {
        if (!Util.debug) return;
        Util.consoleDebug(
                "%s socket %s <- %s isConnected:%s isBound:%s isInputShutdown:%s isOutputShutdown:%s isClosed:%s",
                title,
                clientSocket.getLocalSocketAddress(),
                clientSocket.getRemoteSocketAddress(),
                clientSocket.isConnected(),
                clientSocket.isBound(),
                clientSocket.isInputShutdown(),
                clientSocket.isOutputShutdown(),
                clientSocket.isClosed()
        );
    }

    protected void destroy() {
        if (null != server && !server.isClosed()) {
            try {
                server.close();
            } catch (IOException ignore) {
            }
            server = null;
        }
    }

}

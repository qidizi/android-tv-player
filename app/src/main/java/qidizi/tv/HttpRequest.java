package qidizi.tv;

import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.Charset;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class HttpRequest {
    private static final boolean debug = false;

    protected static String execute(
            String url,
            String method,
            String userAgent,
            String referer,
            String httpProxyHost,
            String httpProxyPort,
            String pageCharset
    ) throws Exception {
        if (null == url || url.isEmpty()) throw new Exception("url 为空");
        boolean isHeadMethod = "HEAD".equalsIgnoreCase(method);
        if (null == referer || referer.isEmpty()) referer = url;
        if (null == userAgent || userAgent.isEmpty())
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0";
        int httpProxyPortInt = Util.getVerifyHttpProxyPort(httpProxyHost, httpProxyPort);
        if (pageCharset == null || pageCharset.isEmpty()) pageCharset = "UTF-8";
        consoleDebug((httpProxyPortInt > 0 ? "走代理" + httpProxyHost + ":" + httpProxyPortInt : "不走代理") + "的http(s)请求：" + url);

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        // 请求http://提示，禁用这个特性 CLEARTEXT communication to www.lih-rehab.com not permitted by network security policy'
        // clientBuilder.connectionSpecs(Arrays.asList(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS));

        if (httpProxyPortInt > 0)
            clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpProxyHost, httpProxyPortInt)));

        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(url);

        if (isHeadMethod) {
            requestBuilder.head();
        } else {
            requestBuilder.get();
        }

        // 添加请求头
        requestBuilder.addHeader("User-Agent", userAgent);
        requestBuilder.addHeader("Referer", referer);

        // 执行请求并获取响应
        try (Response response = clientBuilder.build().newCall(requestBuilder.build()).execute()) {
            String data = response.headers().toString();
            ResponseBody body = response.body();
            if (null != body) {
                // 以报文的方式来
                data += "\r\n" + body.source().readString(Charset.forName(pageCharset));
            }
            return data;
        }
    }

    private static void consoleDebug(String str) {
        if (!debug) return;
        Log.e("qDebug", str);
    }


}

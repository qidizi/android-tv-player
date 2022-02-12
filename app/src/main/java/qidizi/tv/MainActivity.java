package qidizi.tv;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.ImageSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.InputStream;
import java.net.*;
import java.util.Enumeration;

/**
 * TODO api 16 无法正常播放
 */

public class MainActivity extends Activity {
    // ~/android/platform-tools/adb forward tcp:8080 tcp:8080
    // 一般的系统是不允许普通应用开启1024以下的端口的
    private final static int PORT = 8080;
    private final static int dip_font_size = 38;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hasNet();
        createHttp();
        createQr();
    }

    private int dp2px(@SuppressWarnings("SameParameterValue") final float dp) {
        // x 为当前屏幕方向的横向尺寸; 目前在坚果m6上比率并不正确;
        DisplayMetrics point = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(point);
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, point);

    }

    private void createQr() {
        // 创建二维码对象
        TextView qr_view = new TextView(this);
        qr_view.setBackgroundColor(Color.BLACK);
        qr_view.setGravity(Gravity.CENTER);
        qr_view.setTextColor(Color.WHITE);
        qr_view.setTextSize(TypedValue.COMPLEX_UNIT_SP, dip_font_size);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                // 上中
                Gravity.TOP | Gravity.CENTER
        );
        addContentView(qr_view, layout);

        try {
            String ip = getIpv4();
            int size = dp2px(dip_font_size * 4);
            BitMatrix result = new QRCodeWriter().encode(
                    String.format(
                            // 防止idea报换https
                            "http" + "://%s:%s/?noCache=%s",
                            ip,
                            PORT,
                            System.currentTimeMillis()
                    ), BarcodeFormat.QR_CODE, size, size
            );
            Bitmap bitMap = Bitmap.createBitmap(result.getWidth(), result.getHeight(), Bitmap.Config.ARGB_8888);

            for (int y = 0; y < result.getHeight(); y++) {
                for (int x = 0; x < result.getWidth(); x++) {
                    if (result.get(x, y)) {
                        bitMap.setPixel(x, y, Color.WHITE);
                    }
                }
            }

            ImageSpan imageSpan = new ImageSpan(this, bitMap, ImageSpan.ALIGN_BASELINE);
            String qr_placeholder = "1";
            // 保留最后一个字符当qr 码占位码
            final String txt = ip + ":" + PORT + "\n" + qr_placeholder;
            SpannableString spannableString = new SpannableString(txt);
            spannableString.setSpan(
                    imageSpan,
                    txt.length() - qr_placeholder.length(),
                    txt.length(),
                    SpannableString.SPAN_COMPOSING
            );
            qr_view.setText(spannableString);
            qr_view.setVisibility(TextView.VISIBLE);
            shortTip("请扫码访问 " + ip + ":" + PORT);
        } catch (Exception e) {
            shortTip("创建二维码失败:" + e.getMessage());
            finish();
        }
    }

    private void createHttp() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket http;
                try {
                    // 使用端口转发功能,把虚拟机avd端口转发到开发机的8080上,就可以使用 http://127.0.0.1:8080 来访问
                    // ~/android/platform-tools/adb forward tcp:8080 tcp:8080
                    http = new ServerSocket(PORT, 1);
                    http.setReuseAddress(true);

                    if (!http.isBound())
                        throw new Exception("无法绑定端口 " + PORT);

                    //noinspection
                    while (true) accept(http);
                } catch (Exception e) {
                    e.printStackTrace();
                    shortTip("电视端启动失败,请重启本应用试试:" + e.getMessage());
                    finish();
                }
            }
        }).start();
    }

    private void accept(ServerSocket http) {
        Socket client = null;
        InputStream is = null;

        try {
            hasNet();
            client = http.accept();
            is = client.getInputStream();
            try {
                client.setSoTimeout(1000 * 5);
                int maxGet = 1024 * 1024;
                // get 支持的长度: 1M ?放多一位来放\0
                byte[] bytes = new byte[maxGet + 1];

                // 找到 rn rn标志
                int i = 0;

                do {
                    int bt = is.read();

                    if (13 == bt || 10 == bt) {
                        // 到达首个换行,后面全不要了
                        // 先给结束符，防止越界
                        bytes[i] = '\0';
                        break;
                    }

                } while (i++ < maxGet);

                String get = "GET /";

                if (i <= get.length())
                    throw new Exception("http报文不符合标签，只支持GET请求");

                // 取header
                String str = new String(bytes);
                // 释放内存
                //noinspection UnusedAssignment
                bytes = null;

                if (!str.startsWith(get))
                    throw new Exception("仅支持GET请求，却得到：\n\n" + str);

                str = str.split(" ")[1].split("\\?")[0].substring(1);

                if (str.isEmpty()) {
                    shortTip("请提供querystring：&url=视频网址");
                } else {
                    Uri uri = Uri.parse(str);
                    String videoUrl = uri.getQueryParameter("url");

                    if (null == videoUrl || videoUrl.isEmpty()) {
                        shortTip("请提供querystring：&url=视频网址");
                    } else {
                        playUrl(videoUrl);
                    }
                }

                String html = "/res/raw/index.html";

                try (InputStream htmlIs = R.raw.class.getResourceAsStream(html)) {
                    if (null == htmlIs) {
                        shortTip("无效资源 " + html);
                        finish();
                        return;
                    }

                    // 200k缓存
                    byte[] body = new byte[1024 * 200];
                    int body_len = 0, read;

                    while ((read = htmlIs.read(body, body_len, 1024)) > -1) {
                        body_len += read;
                    }

                    client.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n"
                                    + "Content-Type: text/html;charset=utf-8\r\n"
                                    + "Content-Length: " + body_len + "\r\n"
                                    + "\r\n"
                    ).getBytes());

                    if (body_len > 0)
                        client.getOutputStream().write(body);
                }
            } catch (Exception e) {
                e.printStackTrace();
                shortTip(e.getMessage());
            }
        } catch (Exception e) {
            shortTip("建立socket失败:" + e.getMessage());
            e.printStackTrace();
            finish();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignore) {

                }
            }

            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignore) {

                }
            }
        }
    }

    private void hasNet() {
        if (PackageManager.PERMISSION_DENIED == checkCallingOrSelfPermission(Manifest.permission.INTERNET)) {
            shortTip("请授予本应用网络权限再试");
            finish();
        }
    }

    private void longTip(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void shortTip(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void playUrl(final String url) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("video", url));
        Uri uri = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/*");

        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        } else {
            shortTip("无法调起外围视频播放器");
            return;
        }

        shortTip("已经复制到剪切板，及向系统发起外围播放器播放：" + url);
    }

    private String getIpv4() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface nif = en.nextElement();
                for (Enumeration<InetAddress> enumIps = nif.getInetAddresses(); enumIps.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIps.nextElement();
                    if (!inetAddress.isLoopbackAddress() && (inetAddress instanceof Inet4Address)) {
                        String ip = inetAddress.getHostAddress();
                        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
                            return ip;
                        }

                        if (ip.startsWith("172.")) {
                            int sub = Integer.parseInt(ip.split("\\.")[1]);

                            if (sub >= 16 && sub <= 31)
                                return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            longTip("获取ip失败：\n\n" + e.getMessage());
            finish();
        }
        return null;

    }
}

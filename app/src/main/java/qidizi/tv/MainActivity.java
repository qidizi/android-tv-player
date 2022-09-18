package qidizi.tv;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.*;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.InputStream;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    // ~/android/platform-tools/adb forward tcp:8000 tcp:8000 来影射avd中的端口到物理机
    // 一般的系统是不允许普通应用开启1024以下的端口的
    private final static int PORT = 8000;
    // activity 生命周期  https://developer.android.google.cn/guide/components/activities/activity-lifecycle?hl=zh-cn
    private static ServerSocket http;
    private TextView toastTv = null;
    private String url = "";
    private ImageView imageView = null;
    // 消息缓存，用来显示多条消息，但是可视区域有限且属于tv不方便操作，机制是保障最后的n条消息能够显示
    // 需要注意的是，当前并不理会总消息显示是否会超出可见区域，比如一条消息要是过长，显示不全，也只能滚动
    private final List<String> msgCache = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 目前小米手机的悬浮窗口获取的大小并不准确，且退出悬浮没有触发
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        // 隐藏标题
        getActionBar().hide();
        // 隐藏标题，这个方案好像有时有问题，在小米9上
        // this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 隐藏状态行
        //this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 创建qr code 图片容器
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                screenWidth / 2,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        layout.gravity = Gravity.START | Gravity.TOP;
        imageView = new ImageView(this);
        addContentView(imageView, layout);

        // 创建提示view
        layout = new FrameLayout.LayoutParams(
                screenWidth / 2,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        layout.gravity = Gravity.END | Gravity.TOP;
        toastTv = new TextView(this);
        addContentView(toastTv, layout);
        toastTv.setPadding(10, 10, 10, 10);
        // 这个是设置字体对齐方式
        toastTv.setGravity(Gravity.START | Gravity.TOP);
        // 不指定配色，以系统默认的
        toastTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        // 支持滚动条?
        toastTv.setClickable(true);
        // 只能上下滚动,true 会变成左右滚动
        toastTv.setHorizontallyScrolling(false);
        toastTv.setFocusable(true);
        toastTv.setMovementMethod(new ScrollingMovementMethod());
        createHttp();
    }

    private void refreshQr() {
        url = getHttpUrl();
        if (null == url) {
            return;
        }
        // 创建二维码对象
        QRCodeWriter writer = new QRCodeWriter();
        try {
            int size = 1024;
            BitMatrix bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            imageView.setImageBitmap(bmp);
            tip("");
        } catch (Exception e) {
            e.printStackTrace();
            tip("创建二维码失败:" + e.getMessage());
        }
    }

    private void tip(final String msg) {
        // 追加到最后
        msgCache.add(msg);
        // 不含固定提示那条
        int maxMsgCache = 15;
        if (msgCache.size() > maxMsgCache) {
            msgCache.remove(0);
        }

        runOnUiThread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("\n");

                for (int i = msgCache.size() - 1; i >= 0; i--) {
                    // 因为最新消息在最后，要倒着取
                    sb.append("\n").append(msgCache.get(i));
                }

                toastTv.setText(String.format(getResources().getString(R.string.tipTpl), url, sb));
            } catch (Exception e) {
                e.printStackTrace();
                // 如果在activity中显示失败，就换这个方式
                toast("无法在textView中显示信息：" + msg);
            }
        });
    }

    @Override
    protected void onResume() {
        refreshQr();
        // 这个不能放到 super.onResume 后面，否则会导致出错
        // android.os.BinderProxy cannot be cast to android.app.serverTransaction.ClientTransaction
        // 放在这里，小米 9 悬浮窗口退出都无法刷新
        // refreshLayout();
        super.onResume();
    }

    private void createHttp() {
        if (null != http) {
            tip("http服务已启动，不能再次启动");
            return;
        }

        // aide 不支持 Lambda
        new Thread(() -> {
            try {
                // 使用端口转发功能,把虚拟机avd端口转发到开发机的8080上,就可以使用 http://127.0.0.1:8080 来访问
                // ~/android/platform-tools/adb forward tcp:8080 tcp:8080
                // 同时只允许一个请求,其它会被拒绝
                int maxConnections = 1;
                http = new ServerSocket(PORT, maxConnections);
                http.setReuseAddress(true);
            } catch (Exception e) {
                httpClose();
                tip("http服务启动失败：" + e.getMessage());
            }

            if (!http.isBound()) {
                tip(PORT + " 端口绑定失败");
                httpClose();
                return;
            }

            //noinspection
            while (!http.isClosed())
                accept(http);
        }).start();
    }

    private boolean haveNet() {
        // 在小米 9，未授权访问网络，也返回了成功，但是首次会弹出授予网络的提示
        if (PackageManager.PERMISSION_DENIED == checkCallingOrSelfPermission(Manifest.permission.INTERNET)) {
            tip("请授予本应用网络权限再试");
            return false;
        }

        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager == null) {
            tip("无法获取用来判断联网状态的 ConnectivityManager");
            return false;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            //noinspection deprecation
            NetworkInfo nwInfo = connectivityManager.getActiveNetworkInfo();

            if (null == nwInfo) {
                tip("无法判断联网状态：获取活动连接失败");
                return false;
            }

            //noinspection deprecation
            if (nwInfo.isConnected()) return true;
        } else {
            Network nw = connectivityManager.getActiveNetwork();
            if (nw == null) {
                tip("无法判断联网状态：获取活动连接失败");
                return false;
            }
            NetworkCapabilities actNw = connectivityManager.getNetworkCapabilities(nw);
            if (actNw == null) {
                tip("无法判断联网状态：获取活动连接详情失败");
                return false;
            }
            if (actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return true;
        }

        tip("无法判断联网状态");
        return false;
    }

    private void accept(ServerSocket http) {
        if (!haveNet()) return;
        //aide 不支持 try管理res
        try (Socket client = http.accept(); InputStream is = client.getInputStream()) {
            // 因为tcp实现非常简单，基本上一下就能确认收到的长度，所以，只取一回就断开连接即可
            int maxGet = client.getReceiveBufferSize();
            // get 支持的长度，一般的 requestString 并不需要太长
            byte[] bytes = new byte[maxGet];
            int i = is.read(bytes, 0, maxGet);
            // 有时读取可能小于分配长度
            String str = new String(bytes, 0, i - 1);

            if (!Pattern.matches("^GET [^ ]+ HTTP/\\S+[\r\n][\\s\\S]*$", str))
                throw new Exception("非法GET请求：" + str);

            // GET HTTP/1.0 /a/b/c.html?a=b
            str = str.split(" ", 3)[1];
            Uri uri = Uri.parse(str);
            String videoUrl = uri.getQueryParameter("url");

            if (null != videoUrl && !videoUrl.isEmpty()) {
                playUrl(videoUrl);
            } else if (str.contains("url=")) {
                // 只有提交时才提醒
                tip("get请求缺失：&url=视频页面url");
            }

            String html = "/res/raw/index.html";
            try (InputStream htmlIs = R.raw.class.getResourceAsStream(html)) {
                if (null == htmlIs)
                    throw new Exception("无效资源 " + html);

                int bodyLen = 1024 * 50;
                byte[] body = new byte[bodyLen];
                bodyLen = htmlIs.read(body);
                client.getOutputStream().write((
                        "HTTP/1.1 200 OK\r\n"
                                + "Content-Type: text/html;charset=utf-8\r\n"
                                + "Content-Length: " + bodyLen + "\r\n"
                                + "\r\n"
                ).getBytes());

                if (bodyLen > 0)
                    client.getOutputStream().write(body);
                // 提示浏览器内容已经完结
                client.getOutputStream().flush();
                client.getOutputStream().close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 如果联网权限未得到，这里会报
            // android.system.ErrnoException: accept failed: EACCES (Permission denied)
            tip("处理请求失败:" + e.getMessage());
        }
    }

    private String getHttpUrl() {
        String ip = null;
        Enumeration<NetworkInterface> en;

        try {
            en = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            tip("获取电视ip失败：" + e.getMessage());
            return null;
        }

        while (en.hasMoreElements()) {
            NetworkInterface nif = en.nextElement();
            for (Enumeration<InetAddress> enumIps = nif.getInetAddresses(); enumIps.hasMoreElements(); ) {
                InetAddress inetAddress = enumIps.nextElement();
                if (inetAddress.isLoopbackAddress() || !(inetAddress instanceof Inet4Address)) continue;
                String address = inetAddress.getHostAddress();
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

        if (null == ip) {
            tip("推算电视的ip失败");
            return null;
        }

        return String.format(
                // 防止idea报换https
                "http" + "://%s:%s"
                , ip
                , PORT
                // ,System.currentTimeMillis()
        );
    }

    private void playUrl(final String url) {
        // 新线程处理
        new Thread(() -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            // 指定以firefox打开url;用 setComponent 只是打开app，但是不访问url；
            intent.setPackage("org.mozilla.firefox");
            tip("尝试firefox打开..." + url);

            try {
                // 坚果投影 6.x系统要放到ui进程
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                // android.content.ActivityNotFoundException:
                // Unable to find explicit activity class {org.mozilla.firefox/org.mozilla.firefox.App};
                // have you declared this activity in your AndroidManifest.xml?
                tip("无法拉起firefox(可能未在tv上安装？)");
            } catch (Exception e) {
                e.printStackTrace();
                tip("拉起firefox失败：" + e.getMessage());
            }
        }).start();
    }

    private void toast(final String str) {
        runOnUiThread(() -> {
            try {
                // 确保操作处于ui线程
                Toast.makeText(this, str, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                System.out.printf("toast 内容：%s，异常如下", str);
                e.printStackTrace();
            }
        });
    }

    @Override
    protected void onDestroy() {
        httpClose();
        super.onDestroy();
    }

    private void httpClose() {
        if (null == http || http.isClosed()) return;
        long start = System.currentTimeMillis();
        try {
            http.close();
            http = null;
        } catch (Exception ignore) {

        }

        System.out.printf("关闭http耗时 %s 毫秒", System.currentTimeMillis() - start);
    }
}

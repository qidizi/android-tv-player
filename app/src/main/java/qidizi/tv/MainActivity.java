package qidizi.tv;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.media.AudioManager;
import android.net.*;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.net.Proxy;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// https://stackoverflow.com/questions/21814825/you-need-to-use-a-theme-appcompat-theme-or-descendant-with-this-activity

public class MainActivity extends Activity {
    private ExoPlayer exoPlayer = null;
    // ~/android/platform-tools/adb forward tcp:8000 tcp:8000 来影射avd中的端口到物理机
    // 一般的系统是不允许普通应用开启1024以下的端口的
    private final static int PORT = 8888;
    // activity 生命周期
    // https://developer.android.google.cn/guide/components/activities/activity-lifecycle?hl=zh-cn
    private static ServerSocket http;
    private ImageView qrCodeImageView = null;
    private TextView toastView = null;
    private TextView playerStateView = null;
    private final LinkedList<String> toastMsg = new LinkedList<>();
    private float baseTextSizeSp = 14f;
    private int qrSize = 0;
    private String videoUrl = "";
    private int playbackState = 0;
    private int playerErrorCode = 0;
    private long durationMs = 0;
    private long positionMs = 0;
    private final Handler secHandler = new Handler(Looper.getMainLooper());
    private final Runnable secRunnable = new Runnable() {
        private int times = 0;

        @Override
        public void run() {
            times++;
            secHandler.postDelayed(this, 1000);// 固定1秒执行一次，因为view.setText太快会闪退

            if (null != exoPlayer) {
                // 播放器本身不提供定时刷新，需要自行秒级刷新播放进度
                positionMs = exoPlayer.getCurrentPosition();
            }

            if (times >= 15) {
                // 太快，可能会导致如出错消息未看到就删除了，比如5
                if (!toastMsg.isEmpty()) toastMsg.removeLast();
                times = 0;
            }
            // 如果速度太快会导致Shutting down VM，app闪退：FATAL EXCEPTION: main;已知同时开启4个页面自动on Load刷新就会出现
            // java.util.ConcurrentModificationException
            // at java.util.LinkedList$ListItr.checkForCoModification(LinkedList.java:966)
            // at java.util.LinkedList$ListItr.next(LinkedList.java:888)
            // at **.tv.MainActivity$1$$ExternalSyntheticBackport0.m(D8$$SyntheticClass:0)
            // at **.tv.MainActivity$1.run(MainActivity.java:128)
            // 消息可能会比较多，显示只显示前面一部分不超过大部分设备屏幕即可
            String text = "";
            if (!toastMsg.isEmpty()) {
                int max = Math.min(40, toastMsg.size() - 1);
                text = String.join("\n", toastMsg.subList(0, max));
            }

            toastView.setText(text);
        }
    };
    final private boolean debug = false;

    @SuppressLint("DefaultLocale")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 隐藏标题;这句必须放在其它代码之前，super.onCreate之后
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.main);

        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int screenWidth = displayMetrics.widthPixels;

        // 根据屏幕分辨率调整字体大小// 小米9手机density:2.75 densityDpi:440 (1080x2221) ；坚果投影JmGO_M6
        // density:0.50 densityDpi:80 (864x480) ；
        // 字体在mi9上用16sp能看清楚，投影却太小了，按手机这个大小来动态调整

        if (screenWidth < 1024) {
            // 如坚果m6 density:0.50 densityDpi:80 (864x480)
            baseTextSizeSp *= 3;
        }

        qrCodeImageView = findViewById(R.id.qrCodeImageView);
        toastView = findViewById(R.id.toastView);
        toastView.setTextSize(baseTextSizeSp);
        playerStateView = findViewById(R.id.playerStateView);
        playerStateView.setTextSize(baseTextSizeSp);
        createHttp();
        createPlayer();
        createQr();
        // 立刻执行
        secRunnable.run();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            // 抬起手指时处理，方便手机调试
            if (exoPlayer.isPlaying()) {
                pause();
            } else {
                play();
            }
        }
        return super.onTouchEvent(event);
    }

    private void createPlayer() {
        // 创建播放器
        exoPlayer = new ExoPlayer.Builder(this).build();
        // 自定义播放界面
        // https://developer.android.google.cn/media/media3/ui/customization?hl=zh-cn
        exoPlayer.addListener(new Player.Listener() {
            // 用于了解player事件发生顺序及包含信息，正式时不需要打开
            private void debugEvents(Player.Events events) {
                StringBuilder en = new StringBuilder();

                for (int i = 0; i < events.size(); i++) {
                    en.append('\n').append(getPlayerEventDesc(events.get(i)));
                }
                consoleDebug("onEvents:%s", en.toString());
            }

            @SuppressLint("DefaultLocale")
            @Override
            public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
                Player.Listener.super.onEvents(player, events);
                if (debug) debugEvents(events);

                if (events.contains(Player.EVENT_PLAYER_ERROR)) {
                    PlaybackException error = player.getPlayerError();
                    if (null != error) {
                        // 注意考虑清0时机
                        playerErrorCode = error.errorCode;
                        String videoUrl = getVideoUrl();
                        toast(String.format("无法播放%s：%s(%d) %s", videoUrl, error.getMessage(), error.errorCode,
                                Objects.requireNonNull(error.getCause()).getMessage()));
                    }
                }

                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                    // seek后，要刷新当前进度
                    positionMs = exoPlayer.getCurrentPosition();
                    // 同时渲染下
                    renderPlayerState();
                }

                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    playbackState = player.getPlaybackState();

                    if (playbackState == Player.STATE_READY) {
                        playerErrorCode = 0;
                        // 媒体已准备好播放，现在可以安全地获取总时长
                        durationMs = exoPlayer.getDuration();
                    }

                    if (player.isPlaying()) {
                        // 转为播放中
                        playerErrorCode = 0;
                        // 立刻隐藏阻挡元素，不用延时，需要按暂停即可查看
                        qrCodeImageView.setVisibility(View.INVISIBLE);
                        playerStateView.setVisibility(View.INVISIBLE);
                        toastView.setVisibility(View.INVISIBLE);
                    } else {
                        // 非播放状态
                        // 立刻先保存播放进度，以确保变成暂停是同步的
                        positionMs = exoPlayer.getCurrentPosition();
                        qrCodeImageView.setVisibility(View.VISIBLE);
                        playerStateView.setVisibility(View.VISIBLE);
                        // 无脑显示，如果内容空，是透明的
                        toastView.setVisibility(View.VISIBLE);
                    }
                }

                if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYER_ERROR)) {
                    // 播放状态变化（虽说播放时不用）、正播放非播放中切换（它与前者有可能不同时出现）、出错，更新UI；
                    playbackState = player.getPlaybackState();
                    // 要放到获取的逻辑的后面
                    renderPlayerState();
                }
            }
        });
        ((PlayerView) findViewById(R.id.videoView)).setPlayer(exoPlayer);
    }

    @NonNull
    private String getVideoUrl() {
        String videoUrl = "视频链接";
        MediaItem mediaItem = exoPlayer.getCurrentMediaItem();
        if (null != mediaItem) {
            MediaItem.LocalConfiguration localConfiguration = mediaItem.localConfiguration;
            if (localConfiguration != null) {
                // 仅当媒体项是通过 MediaItem.fromUri() 或类似方式加载的远程资源时，uri 才会有效
                videoUrl += "local:" + localConfiguration.uri;
            }
        }
        return videoUrl;
    }

    @SuppressLint("DefaultLocale")
    private String msHuman(long l) {
        l /= 1000;
        return String.format("%02d:%02d:%02d", l / 3600, l % 3600 / 60, l % 60);
    }

    private void renderPlayerState() {
        playerStateView.setText(String.format("%s (%s/%s)", stateHuman(playbackState), msHuman(positionMs), msHuman(durationMs)));
    }

    private String stateHuman(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "空闲中";
            case Player.STATE_BUFFERING:
                return "缓冲中";
            case Player.STATE_READY:
                // 这2个状态是否准确需要试用
                if (exoPlayer.getPlayWhenReady())
                    return "播放中";
                return "暂停中";
            case Player.STATE_ENDED:
                return "已播完";
            default:
                return "未定义";
        }
    }

    private void createHttp() {
        if (null != http) {
            toast("http服务运行中，忽略启动请求");
            return;
        }

        if (!haveNet()) {
            // 没网络权限，是无法创建http服务
            return;
        }

        new Thread(() -> {
            try {
                // 使用端口转发功能,把虚拟机avd端口转发到开发机的8080上,就可以使用 http://127.0.0.1:8080 来访问
                // ~/android/platform-tools/adb forward tcp:8080 tcp:8080
                // 同时只多个请求
                int maxConnections = 10;
                http = new ServerSocket(PORT, maxConnections);
                http.setReuseAddress(true);
            } catch (Exception e) {
                debugException(e, "http服务器创建socket与绑定ip时");
                httpClose();
                toast("http服务启动失败：" + e.getMessage());
                // 不要往下走
                return;
            }

            if (!http.isBound()) {
                toast(PORT + " 端口绑定失败");
                httpClose();
                return;
            }

            int si = 0;
            consoleDebug("http服务启动\n");
            toast("http服务启动成功");
            // 要加入null判断，防止异常后，http变成null
            // noinspection
            while (null != http && !http.isClosed()) {
                Socket clientSocket;
                try {
                    // 阻塞直到有新的客户端连接
                    clientSocket = http.accept();
                    final int fsi = ++si;
                    socketDebug("100", si, clientSocket);
                    // 为每个新的客户端连接创建一个新的线程来处理;注意 clientSocket 不能在这个线程中控制关闭，否则下层进程无法操作了
                    new Thread(() -> httpAccept(clientSocket, fsi)).start();

                    socketDebug("101", si, clientSocket);

                } catch (Exception e) {
                    debugException(e, "接受新连接时");
                }
            }
            consoleDebug("http服务关闭\n");
        }).start();
    }

    @SuppressLint("DefaultLocale")
    private void httpAccept(Socket client, int si) {
        socketDebug("201", si, client);

        try (client; InputStream is = client.getInputStream(); OutputStream os = client.getOutputStream()) {
            // 因为tcp实现非常简单，基本上一下就能确认收到的长度，所以，只取一回就断开连接即可
            // 最大支持1MB 的请求，应该够用了
            int maxGet = 1024 * 1024;
            // 缓冲与读取都要比最大支持大1位，方便下面判断是否超出
            int maxGetRead = maxGet + 1;
            byte[] buffer = new byte[maxGetRead];
            socketDebug("202", si, client);
            int bytesRead = is.read(buffer, 0, maxGetRead);
            socketDebug("%d请求读取长度：" + bytesRead, si, client);

            if (0 == bytesRead) {
                // 没内容是0
                throw new IOException("出错了！你提交的请求http报文长度为0");
            } else if (bytesRead < 0) {
                // 结尾了是-1;比如请求未结束，浏览器就刷新重新请求，浏览器就会终止上个请求，所以是-1
                throw new IOException("出错了！你提交的请求http报文长度为:" + bytesRead);
            } else if (bytesRead > maxGet) {
                // 超过了最大长度
                throw new IOException("出错了！请求时提交超过 " + maxGet + " bytes");
            }

            socketDebug("203", si, client);
            String str = new String(buffer, 0, bytesRead);
            socketDebug(String.format("请求内容(%d/%d bytes)[start]%s[end]\n", bytesRead, maxGet, str), si, client);

            if (!Pattern.matches("^GET [^ ]+ HTTP/\\S+[\r\n][\\s\\S]*$", str)) {
                toast("已忽略非GET控制请求：" + str.substring(0, 20) + "...");
                throw new Exception("仅支持GET请求");
            }

            // GET /a/b/c.html?a=b HTTP/1.0
            str = str.split(" ", 3)[1];
            Uri uri = Uri.parse(str);
            final String action = uri.getQueryParameter("action");

            if (null == action || action.isEmpty()) {
                // 不是提交状态，无条件返回html内容
                // 不能用htmlIs.available();，它并不保证是整个文件的长度
                int bodyLen;
                // 如果文件被压缩，就会报错，需要在build.gradle中配置 aaptOptions {noCompress 'html'}
                // 报 android.content.res.Resources$NotFoundException: File res/raw/index.html from resource ID #0x7f100000
                // 可能是前面压缩，修改一下html再build即可
                try (AssetFileDescriptor fd = getResources().openRawResourceFd(R.raw.index)) {
                    if (null == fd) throw new Exception("index.html资源文件信息读取失败");
                    bodyLen = (int) fd.getLength();
                }
                socketDebug("html bytes:" + bodyLen, si, client);
                try (InputStream htmlIs = getResources().openRawResource(R.raw.index)) {
                    socketDebug("204", si, client);
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html;charset=utf-8\r\nContent-Length: " + bodyLen
                            + "\r\n\r\n").getBytes());
                    socketDebug("205", si, client);
                    int ch;
                    while ((ch = htmlIs.read()) != -1) {
                        os.write(ch);
                    }
                    socketDebug("206", si, client);
                    os.flush();
                    socketDebug("207", si, client);
                    toast("已返回页面。");
                }
                return;
            }
            socketDebug("208", si, client);

            switch (action) {
                case "playUrl":
                    responseUrl(os, playUrl(uri), "片源:" + uri.getQueryParameter("videoUrl"));
                    return;
                case "tryPlayPause":
                    responseUrl(os, encodeOk("指令" + tryPlayPause(uri.getBooleanQueryParameter("isPlay", false))),
                            "请求暂停/播放");
                    return;
                case "volumeCtrl":
                    responseUrl(os, encodeOk("指令" + volumeCtrl(uri.getQueryParameter("how"))), "请求控制声音");
                    return;
                case "seek":
                    String v = uri.getQueryParameter("seekMinutes");
                    if (null == v)
                        v = "0";
                    int seekMinutes = Integer.parseInt(v);
                    seek(seekMinutes);
                    responseUrl(os, encodeOk("跳转到" + seekMinutes + "分钟"), "跳转到");
                    return;
                case "getInfo":
                    responseUrl(os, getInfo(), "获取信息");
                    return;
                case "fetchOtherOriginText":
                    responseUrl(os, fetchOtherOriginText(uri), "跨源请求");
                    return;
                default:
                    responseUrl(os, encodeFail("不支持的控制请求：" + action), "未定义控制请求" + action);
            }
            socketDebug("209", si, client);
        } catch (Exception e) {
            debugException(e, si + "-解析http入请求时");
            // 如果联网权限未得到，这里会报  （避免studio语法提示）
            // android.system.ErrnoException: accept failed: E A C C E S (Permission denied)
            toast("无法处理你请求:" + e.getMessage());
        }
    }

    private boolean haveNet() {
        // 在小米 9，未授权访问网络，也返回了成功，但是首次会弹出授予网络的提示
        if (PackageManager.PERMISSION_DENIED == checkCallingOrSelfPermission(Manifest.permission.INTERNET)) {
            toast("请授予本应用网络权限再试");
            return false;
        }

        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager == null) {
            toast("无法获取用来判断联网状态的 ConnectivityManager");
            return false;
        }

        Network nw = connectivityManager.getActiveNetwork();
        if (nw == null) {
            toast("无法判断联网状态：获取活动连接失败");
            return false;
        }
        NetworkCapabilities actNw = connectivityManager.getNetworkCapabilities(nw);
        if (actNw == null) {
            toast("无法判断联网状态：获取活动连接详情失败");
            return false;
        }
        if (actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))
            return true;

        toast("无法判断联网状态");
        return false;
    }

    private String fetchOtherOriginText(Uri uri) {
        String method = uri.getQueryParameter("method");
        String src = uri.getQueryParameter("src");
        if (null == src || src.isEmpty())
            return encodeFail("src为空");
        if (!src.matches("^https?://.+"))
            return encodeFail("src只能是http(s):" + src);
        String userAgent = uri.getQueryParameter("userAgent");
        String referer = uri.getQueryParameter("referer");
        OkHttpClient client = new OkHttpClient();
        Request.Builder builder = new Request.Builder().url(src);
        // builder.method(method,null) 这样用，当method小写时返回400
        if ("head".equalsIgnoreCase(method))
            builder.head();
        else
            builder.get();
        if (null != userAgent && !userAgent.isEmpty())
            builder.addHeader("User-Agent", userAgent);
        if (null != referer && !referer.isEmpty())
            builder.addHeader("Referer", referer);
        Request request = builder.build();

        try (Response response = client.newCall(request).execute()) {
            okhttp3.ResponseBody body = response.body();
            String result = encodeOk("请求完成");
            if (null == body)
                result += "&body=";
            else
                result += "&body=" + urlEncode(body.string());
            result += "&header=" + urlEncode(response.headers().toString());
            return result;
        } catch (IOException e) {
            return encodeFail("请求对方服务器失败" + e.getMessage());
        }
    }

    @SuppressLint("DefaultLocale")
    private String getInfo() {
        DisplayMetrics displayMetrics = getResources().getDisplayMetrics();
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;
        float density = displayMetrics.density;
        int densityDpi = displayMetrics.densityDpi;
        String devName = Build.MODEL;
        return String.format("%s&devName=%s&devInfo=%s&videoUrl=%s&durationMs=%d&positionMs=%d&playErrorCode=%d",
                encodeOk("播放器信息"),
                urlEncode(devName),
                urlEncode(String.format("%s %s density:%.2f densityDpi:%d (%dx%d) Android %s %s %s textSize:%.2fsp qrSize:%d\n\n欢迎使用%s！",
                        Build.BRAND, devName, density, densityDpi, screenWidth, screenHeight, Build.VERSION.RELEASE,
                        Build.MANUFACTURER, Build.PRODUCT, baseTextSizeSp, qrSize, getString(R.string.app_name))),
                urlEncode(videoUrl),
                durationMs,
                positionMs,
                playerErrorCode);
    }

    private void seek(int seekMinutes) {
        runOnUiThread(() -> exoPlayer.seekTo((long) seekMinutes * 60 * 1000));
    }

    @OptIn(markerClass = UnstableApi.class)
    private String playUrl(Uri uri) {
        String videoUrlTmp = uri.getQueryParameter("videoUrl");
        final String refererUrl = uri.getQueryParameter("refererUrl");
        final String userAgent = uri.getQueryParameter("userAgent");
        String httpProxyHost = uri.getQueryParameter("httpProxyHost");
        final String httpProxyPort = uri.getQueryParameter("httpProxyPort");
        if (null == videoUrlTmp || null == refererUrl || null == userAgent || videoUrlTmp.isEmpty()
                || refererUrl.isEmpty() || userAgent.isEmpty()) {
            return encodeFail("必须参数为空");
        }

        // 重置某些信息
        videoUrl = videoUrlTmp;
        playerErrorCode = 0;
        durationMs = 0;
        positionMs = 0;
        // 创建 MediaItem
        MediaItem mediaItem = MediaItem.fromUri(videoUrl);
        // 自动识别媒体类型，mp4？m3u8？
        // 在某些旧设备，可能缺失某些新出现的证书机构的证书，然后导致提示如 trust anchor for certification path not
        // found
        // 见这介绍
        // https://developer.android.google.cn/privacy-and-security/security-ssl?hl=zh-cn#CommonProblems
        // 解决方案是添加新机构的证书到app上
        // https://developer.android.google.cn/privacy-and-security/security-config?hl=zh-cn#TrustingAdditionalCas
        // 如在android 7的坚果 m6遇到播放远程视频就会提示 trust anchor for certification path not found，

        // 优先选用okHttp实现网络传输，而不是内置默认，因为设备不同可能会导致缺失能力
        // https://developer.android.google.cn/media/media3/exoplayer/network-stacks?hl=zh-cn
        // OkHttp works on Android 5.0+ (API level 21+) and Java 8+.

        OkHttpClient.Builder okHttpClientBuilder = new OkHttpClient.Builder();
        if (null != httpProxyHost && null != httpProxyPort && !httpProxyHost.isEmpty()
                && !httpProxyPort.isEmpty()) {
            if (!httpProxyPort.matches("^\\d+$"))
                return encodeFail("代理端口不是数字:" + httpProxyPort);
            int port = Integer.parseInt(httpProxyPort);
            if (port < 1025)
                return encodeFail("代理端口必须大于1024:" + httpProxyPort);
            if (port > 65535)
                return encodeFail("代理端口必须小于65535:" + httpProxyPort);
            if (!httpProxyHost.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")
                    && !httpProxyHost.matches("^([a-zA-Z\\d-]+\\.)+[a-zA-z]{2,}$"))
                return encodeFail("代理主机即不是ipv4也不是域名:" + httpProxyHost);
            okHttpClientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpProxyHost, port)));
            httpProxyHost += ":" + httpProxyPort;
        } else {
            httpProxyHost = "未设置";
        }

        okHttpClientBuilder.followRedirects(true);
        DefaultMediaSourceFactory mediaSource = new DefaultMediaSourceFactory(
                new OkHttpDataSource.Factory(okHttpClientBuilder.build())
                        .setUserAgent(userAgent)
                        .setDefaultRequestProperties(Map.of("Referer", refererUrl)));
        // 设置媒体源并准备播放
        // 2个方法必须在main线程
        runOnUiThread(() -> {
            exoPlayer.setMediaSource(mediaSource.createMediaSource(mediaItem));
            exoPlayer.prepare();
        });
        play();
        return encodeOk(String.format("播放中...\n视频网址: %s\n请求referer: %s\nuserAgent: %s\n免用户密码的http代理: %s",
                videoUrl, refererUrl, userAgent, httpProxyHost));
    }

    private String encodeOk(String value) {
        return "ok=" + urlEncode(value);
    }

    private String encodeFail(String value) {
        return "fail=" + urlEncode(value);
    }

    private String volumeCtrl(String how) {
        // 调整的是系统的时间，不要调整本播放器的
        if (null == how)
            how = "null";
        // 经过测试在mi9 android 11系统中，切换到其它应用窗口（本app窗口在背后了），这个调整没有效果；
        // 目前普通app暂未找到解决方案
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        switch (how) {
            case "0":
                audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                return "静音切换";
            case "1":
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return "增加音量";
            case "-1":
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return "减少音量";
            default:
                return "未定义的音量操作：" + how;
        }
    }

    private String tryPlayPause(boolean isPlay) {
        // exoPlayer.getPlayWhenReady() 需要在主线程操作，暂时不判断，无脑发
        if (isPlay) {
            play();
            return "播放";
        } else {
            pause();
            return "暂停";
        }
    }

    private void play() {
        // 必须在ui线程播放
        runOnUiThread(() -> exoPlayer.play());
    }

    private void pause() {
        // 必须在ui线程播放
        runOnUiThread(() -> exoPlayer.pause());
    }

    private void responseUrl(OutputStream os, String url, String tip) throws IOException {
        byte[] bodyByte = url.getBytes();
        // 允许跨域请求,这样，可以把html放到其它服务器上都可以使用，并不需要使用app内置的
        os.write((
                "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html;charset=utf-8\r\n"
                        + "Content-Length: " + bodyByte.length + "\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Vary: Origin\r\n"
                        + "\r\n"
                        + url
        ).getBytes());
        os.flush();
        toast(tip);
    }

    private String getTvIp() throws Exception {
        String ip = null;
        Enumeration<NetworkInterface> en;

        try {
            en = NetworkInterface.getNetworkInterfaces();
        } catch (Exception e) {
            debugException(e, "获取本设备网络接口时");
            throw new Exception("无法获得网络接口: " + e.getMessage());
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

    private void toast(final String str) {
        toastMsg.addFirst(str);
    }

    private void httpClose() {
        if (null == http || http.isClosed())
            return;
        long start = System.currentTimeMillis();
        try {
            http.close();
            http = null;
        } catch (Exception e) {
            debugException(e, "关闭http服务器时");
        }

        System.out.printf("关闭http耗时 %s 毫秒", System.currentTimeMillis() - start);
    }

    private String urlEncode(String v) {
        try {
            String code = "UTF-8";
            return URLEncoder.encode(v, code);
        } catch (Exception e) {
            return "url转义失败：" + e.getMessage() + "。待转义文字： " + v;
        }
    }

    private void createQr() {
        String tvIp = "127.0.0.1";

        try {
            tvIp = getTvIp();
        } catch (Exception e) {
            debugException(e, "获取电视ip");
            toast("获取电视lan ip失败：" + e.getMessage());
            this.finish();
        }

        // 绘制二维码
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);

        qrSize = Math.min(size.x, size.y) / 4;
        // 应用 LayoutParams 到二维码图像视图
        qrCodeImageView.getLayoutParams().width = qrCodeImageView.getLayoutParams().height = qrSize;

        String content = "http://" + tvIp + ":" + PORT + "/?r=" + System.currentTimeMillis();
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8"); // 设置字符编码
        hints.put(EncodeHintType.MARGIN, 1); // 设置二维码边距

        try {
            // 生成二维码矩阵
            BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, qrSize, qrSize, hints);

            // 将 BitMatrix 转换为 Bitmap
            Bitmap bitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.RGB_565);
            for (int x = 0; x < qrSize; x++) {
                for (int y = 0; y < qrSize; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            qrCodeImageView.setImageBitmap(bitmap);
            toast("欢迎使用" + getString(R.string.app_name) + "！\n请用微信/浏览器扫码播放视频/控制");
        } catch (Exception e) {
            toast("显示二维码失败：" + e.getMessage());
            debugException(e, "绘制二维码时");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        pause();
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER: // 回车
            case KeyEvent.KEYCODE_DPAD_CENTER: // 遥控确认
                if (exoPlayer.isPlaying()) {
                    pause();
                } else {
                    play();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                exoPlayer.seekBack();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                exoPlayer.seekForward();
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                // 显示信息？
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 隐藏信息？
                break;
            case KeyEvent.KEYCODE_BACK:
                // 忽略返回按键
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) {
            exoPlayer.release();
        }

        if (http != null) {
            try {
                http.close();
            } catch (IOException ignore) {
            }
        }

        secHandler.removeCallbacks(secRunnable);
    }

    private String getPlayerEventDesc(int event) {
        switch (event) {
            case Player.EVENT_AUDIO_ATTRIBUTES_CHANGED:
                return "EVENT_AUDIO_ATTRIBUTES_CHANGED";
            case Player.EVENT_AUDIO_SESSION_ID:
                return "EVENT_AUDIO_SESSION_ID";
            case Player.EVENT_AVAILABLE_COMMANDS_CHANGED:
                return "EVENT_AVAILABLE_COMMANDS_CHANGED";
            case Player.EVENT_CUES:
                return "EVENT_CUES";
            case Player.EVENT_DEVICE_INFO_CHANGED:
                return "EVENT_DEVICE_INFO_CHANGED";
            case Player.EVENT_DEVICE_VOLUME_CHANGED:
                return "EVENT_DEVICE_VOLUME_CHANGED";
            case Player.EVENT_IS_LOADING_CHANGED:
                return "EVENT_IS_LOADING_CHANGED";
            case Player.EVENT_IS_PLAYING_CHANGED:
                return "EVENT_IS_PLAYING_CHANGED";
            case Player.EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED:
                return "EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED";
            case Player.EVENT_MEDIA_ITEM_TRANSITION:
                return "EVENT_MEDIA_ITEM_TRANSITION";
            case Player.EVENT_MEDIA_METADATA_CHANGED:
                return "EVENT_MEDIA_METADATA_CHANGED";
            case Player.EVENT_METADATA:
                return "EVENT_METADATA";
            case Player.EVENT_PLAYBACK_PARAMETERS_CHANGED:
                return "EVENT_PLAYBACK_PARAMETERS_CHANGED";
            case Player.EVENT_PLAYBACK_STATE_CHANGED:
                return "EVENT_PLAYBACK_STATE_CHANGED";
            case Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED:
                return "EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED";
            case Player.EVENT_PLAYER_ERROR:
                return "EVENT_PLAYER_ERROR";
            case Player.EVENT_PLAYLIST_METADATA_CHANGED:
                return "EVENT_PLAYLIST_METADATA_CHANGED";
            case Player.EVENT_PLAY_WHEN_READY_CHANGED:
                return "EVENT_PLAY_WHEN_READY_CHANGED";
            case Player.EVENT_POSITION_DISCONTINUITY:
                return "EVENT_POSITION_DISCONTINUITY";
            case Player.EVENT_RENDERED_FIRST_FRAME:
                return "EVENT_RENDERED_FIRST_FRAME";
            case Player.EVENT_REPEAT_MODE_CHANGED:
                return "EVENT_REPEAT_MODE_CHANGED";
            case Player.EVENT_SEEK_BACK_INCREMENT_CHANGED:
                return "EVENT_SEEK_BACK_INCREMENT_CHANGED";
            case Player.EVENT_SEEK_FORWARD_INCREMENT_CHANGED:
                return "EVENT_SEEK_FORWARD_INCREMENT_CHANGED";
            case Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED:
                return "EVENT_SHUFFLE_MODE_ENABLED_CHANGED";
            case Player.EVENT_SKIP_SILENCE_ENABLED_CHANGED:
                return "EVENT_SKIP_SILENCE_ENABLED_CHANGED";
            case Player.EVENT_SURFACE_SIZE_CHANGED:
                return "EVENT_SURFACE_SIZE_CHANGED";
            case Player.EVENT_TIMELINE_CHANGED:
                return "EVENT_TIMELINE_CHANGED";
            case Player.EVENT_TRACKS_CHANGED:
                return "EVENT_TRACKS_CHANGED";
            case Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED:
                return "EVENT_TRACK_SELECTION_PARAMETERS_CHANGED";
            case Player.EVENT_VIDEO_SIZE_CHANGED:
                return "EVENT_VIDEO_SIZE_CHANGED";
            case Player.EVENT_VOLUME_CHANGED:
                return "EVENT_VOLUME_CHANGED";
        }
        return "UNKNOWN-" + event;
    }

    /**
     * @noinspection SameParameterValue
     */
    private void consoleDebug(String format, Object... vars) {
        if (!debug) return;
        Log.e("qDebug", String.format(format, vars));
    }

    private void socketDebug(String title, int si, Socket client) {
        if (!debug) return;
        // 使用一个不存在值，暂时禁用调试
        if (si > -1) return;
        consoleDebug(
                "%d-%s: 端口%d isClosed:%s isConnected:%s isInputShutdown:%s isOutputShutdown:%s",
                si,
                title,
                client.getPort(),
                client.isClosed(),
                client.isConnected(),
                client.isInputShutdown(),
                client.isOutputShutdown()
        );
    }

    private void debugException(Exception e, String from) {
        Log.e("qDebug", "执行如下操作时报错了：" + from, e);
    }
}

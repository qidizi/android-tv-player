package qidizi.tv_ime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.UrlQuerySanitizer;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.ImageSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.Enumeration;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

/**
 * TODO api 16 无法正常播放
 */

public class MainActivity extends Activity {
    private TextView qr_view = null;
    private Timer tip_timer = null;
    private SimpleExoPlayer simpleExoPlayer = null;
    private PlayerView playerView = null;
    private boolean catch_video = false;
    private WebView webView = null;
    private String seek = "0";
    // ~/android/platform-tools/adb forward tcp:11111 tcp:11111
    private final static int PORT = 11111;
    private final static int dip_font_size = 38;
    // 快进/快退时间
    private final static long seek_step = 1000 * 60 * 3;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 按顺序创建,前的在下层 因为 setElevation 在api21才可用
        create_player();
        create_webview();
        create_http();
        create_qr();
        show_qr(true);
        has_net();

        switch (Build.VERSION.SDK_INT) {
            // 暂用来防止idea提示换成if
            case 15:
            case 16:
                set_tip("当前安卓版本可能无法正常使用,原因:视频解码器缺失,及ssl根证书过期");
                break;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void create_webview() {
        final String blank_url = "about:blank";
        webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient() {

            private boolean block_someone(String url) {
                // 已经拿到了视频,所有的都阻止
                if (catch_video) {
                    set_tip("视频已捕捉到,忽略 " + url);
                    return true;
                }

                String path = url.replaceAll("[?#].*", "").toLowerCase();
                // 这些资源不需要加载
                boolean is_block = Pattern.compile(
                        // 文件扩展
                        ".(png|jpe?g|css|svg|woff|ttf|gif|ico)$|" +
                                // 某些域名
                                "cnzz.com|hm.baidu.com|google.com|zz.bdstatic.com"
                        , Pattern.CASE_INSENSITIVE
                ).matcher(path).find();

                if (is_block) {
                    set_tip("忽略 " + url);
                    info("忽略 " + url);
                    return true;
                }

                // 视频
                if (path.matches(".+\\.(mp4|m3u8)$")) {
                    catch_video = true;
                    set_tip("捕捉到视频:" + url);
                    info("捕获视频:" + url);

                    // 清理内存
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            webView.stopLoading();
                            webView.loadUrl(blank_url);
                        }
                    });
                    play_url(url, seek);
                    return true;
                }

                set_tip("加载 " + url);
                info("加载 " + url);
                return false;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                set_tip("https证书异常,可能无法继续请求,建议换设备 " + error.toString());
                handler.proceed(); // Ignore SSL certificate errors
            }

            // 高的版本,性能更好
            @TargetApi(Build.VERSION_CODES.N)
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 相当于app自己处理这种url
                // 不能用getUrl(), 它需要与ui同线程
                String url = request.getUrl().toString();
                if (url.equals(blank_url))
                    return super.shouldOverrideUrlLoading(view, request);
                if (block_someone(url)) return false;
                return super.shouldOverrideUrlLoading(view, request);
            }

            @SuppressWarnings("deprecation")
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                // 相当于app自己处理这种url
                // 不能用getUrl(), 它需要与ui同线程
                if (url.equals(blank_url)) return null;
                if (block_someone(url)) return new WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        null
                );
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.equals(blank_url)) return;
                super.onPageFinished(view, url);

                if (!catch_video) {
                    set_tip("捕捉视频失败");
                    info("页面已经加载完成,捕捉视频失败");
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        webView.loadUrl(blank_url);
                        webView.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url.equals(blank_url)) return;

                super.onPageStarted(view, url, favicon);
                catch_video = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        webView.setVisibility(View.VISIBLE);
                    }
                });
            }
        });

        // 低版本默认允许
        if (Build.VERSION.SDK_INT >= 21)
            // 允许http加载https
            webView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 允许加载资源
        webView.getSettings().setAllowContentAccess(true);
        // 雇用js引擎
        webView.getSettings().setJavaScriptEnabled(true);
        // 设置浏览器user agent
        webView.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 5.0; SM-G900P Build/LRX21T) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/88.0.4324.192 Mobile Safari/537.36"
        );
        // 允许 sessionStorage 和 localStorage
        webView.getSettings().setDomStorageEnabled(true);
        // 阻止图片
        webView.getSettings().setBlockNetworkImage(true);

        if (Build.VERSION.SDK_INT > 16)
            // 必须用户触摸屏幕,网页视频才能开始播放
            webView.getSettings().setMediaPlaybackRequiresUserGesture(true);

        if (Build.VERSION.SDK_INT > 18)
            // 允许远程调试
            WebView.setWebContentsDebuggingEnabled(true);
        webView.setAlpha(0.4F);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        addContentView(webView, layout);
    }

    private void get_video_url(final String url, final String new_seek) {
        seek = new_seek;
        set_tip("尝试从页面中捕捉视频链接:  " + url);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl(url);
            }
        });
    }

    private void create_player() {
        simpleExoPlayer = new SimpleExoPlayer.Builder(this).build();
        playerView = new PlayerView(this);
        playerView.setPlayer(simpleExoPlayer);
        // 总是显示缓存提示
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        // 自适应分辨率,如4:3,16:9
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setFitsSystemWindows(true);
        playerView.setBackgroundColor(Color.BLACK);
        // 防止快进/退出现切换焦点到非播放/暂停按钮上，出现无法暂停问题
        playerView.setShowRewindButton(false);
        playerView.setShowFastForwardButton(false);
        playerView.setShowNextButton(false);
        playerView.setShowPreviousButton(false);
        // 音量100%,声音大小由设备调节
        simpleExoPlayer.setVolume(1);
        simpleExoPlayer.addListener(new Player.EventListener() {
            @Override
            public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
                // 正在播放中
                // 隐藏qr 码
                // 其它状态都显示qr 码
                show_qr(!player.isPlaying());
            }

            @Override
            public void onPlayerError(@NonNull ExoPlaybackException error) {
                // 当前 2.11.7 版本还是没有解决 缓存慢导致停止播放问题
                // 出错时,只得到这个信息 com.google.android.exoplayer2.source.BehindLiveWindowException
                // 见 这个说明 https://medium.com/google-exoplayer/load-error-handling-in-exoplayer-488ab6908137
                // 见 https://exoplayer.dev/hls.html 中 BehindLiveWindowException
                if (isBehindLiveWindow(error)) {
                    // Re-initialize player at the live edge.
                    Log.d("qidizi_debug", "出现 BehindLiveWindowException 错误,重试");
                    set_tip("噢,要缓冲了,请稍候或换源...[1]");
                    simpleExoPlayer.prepare();
                    return;
                }

                error.printStackTrace();
                String msg;
                switch (error.type) {
                    case ExoPlaybackException.TYPE_REMOTE:
                        msg = "远程组件异常";
                        break;
                    case ExoPlaybackException.TYPE_RENDERER:
                        msg = "视频渲染异常";
                        break;
                    case ExoPlaybackException.TYPE_SOURCE:
                        msg = "视频源异常";
                        break;
                    case ExoPlaybackException.TYPE_UNEXPECTED:
                        msg = "运行时异常";
                        break;
                    default:
                        msg = "未明异常";
                        break;
                }

                // 出错信息不要类名
                msg += ":";
                msg += null == error.getCause() || null == error.getCause().getMessage() ?
                        error.getMessage() : error.getCause().getMessage();
                set_tip(msg);
                Log.d("qidizi_debug", msg);
            }

            private boolean isBehindLiveWindow(ExoPlaybackException e) {
                if (e.type != ExoPlaybackException.TYPE_SOURCE) {
                    return false;
                }
                Throwable cause = e.getSourceException();
                while (cause != null) {
                    if (cause instanceof BehindLiveWindowException) {
                        return true;
                    }
                    cause = cause.getCause();
                }
                return false;
            }
        });
        // 不重复播放
        simpleExoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        addContentView(playerView, layout);
        // 见 https://github.com/google/ExoPlayer/blob/release-v2/library/ui/src/main/res/layout/exo_player_view.xml
        // 错误提示的view
        TextView textView = findViewById(R.id.exo_error_message);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dip_font_size);
        textView.setBackgroundColor(Color.BLACK);
        textView.getBackground().setAlpha(255 / 10);
        // 视频长度时间view
        textView = findViewById(R.id.exo_duration);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dip_font_size);
        // 已播放时间view
        textView = findViewById(R.id.exo_position);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dip_font_size);
    }

    private int dp2px(@SuppressWarnings("SameParameterValue") final float dp) {
        // x 为当前屏幕方向的横向尺寸; 目前在坚果m6上比率并不正确;
        DisplayMetrics point = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(point);
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, point);

    }

    private void create_qr() {
        // 创建二维码对象
        qr_view = new TextView(this);
        qr_view.setBackgroundColor(Color.BLACK);
        qr_view.getBackground().setAlpha(255 / 10);
        qr_view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        // 向右对齐
        qr_view.setGravity(Gravity.RIGHT);
        qr_view.setTextColor(Color.WHITE);
        qr_view.setTextSize(TypedValue.COMPLEX_UNIT_SP, dip_font_size);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                // 整体在屏幕右下
                Gravity.TOP | Gravity.RIGHT
        );
        addContentView(qr_view, layout);
    }

    private void set_tip(final String txt) {
        if (null == playerView)
            return;

        if (null != tip_timer) {
            tip_timer.cancel();
            tip_timer = null;
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                playerView.setCustomErrorMessage(txt);
                tip_timer = new Timer();

                try {
                    tip_timer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            // 操作ui,必须在ui线程操作
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    playerView.setCustomErrorMessage(null);
                                }
                            });
                        }
                    }, 1000 * 5);
                } catch (Exception ignore) {

                }
            }
        });
    }

    private void create_http() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket http;
                try {
                    // 使用端口转发功能,把虚拟机avd端口转发到开发机的11111上,就可以使用 http://127.0.0.1:11111 来访问
                    // ~/android/platform-tools/adb forward tcp:11111 tcp:11111
                    http = new ServerSocket(PORT, 1);
                    http.setReuseAddress(true);

                    if (!http.isBound()) {
                        throw new Exception("无法绑定端口 " + PORT);
                    }

                    set_tip("http启动成功");
                } catch (Exception e) {
                    e.printStackTrace();
                    set_tip("电视端启动失败,请重启本应用试试:" + e.getMessage());
                    return;
                }

                //noinspection
                while (true) accept(http);
            }
        }).start();
    }

    private void accept(ServerSocket http) {
        Socket client = null;
        InputStream is = null;

        try {
            has_net();
            client = http.accept();
            is = client.getInputStream();
            try {
                client.setSoTimeout(1000 * 5);
                // get 支持的长度
                byte[] bytes = new byte[1024];
                int len = is.read(bytes);
                if (-1 == len)
                    throw new Exception("http报文读取异常");

                // 找到 rn rn标志
                int rn_rn = 0, i = 0;

                do {
                    if (13 == bytes[i] || 10 == bytes[i])
                        rn_rn++;
                    else
                        rn_rn = 0;

                    if (4 == rn_rn) {
                        // 把游标移到到body首个byte
                        i++;
                        break;
                    }
                } while (i++ < len);

                if (0 == rn_rn)
                    throw new Exception("http报文缺失\\r\\n\\r\\n");
                int body_index = len - i;
                // 取header
                String str = new String(bytes, 0, i);
                // set_tip(str, this);

                if (str.startsWith("GET /")) {
                    // get 请求,总是当成html文本资源来处理
                    get_resource(client.getOutputStream(), str);
                    return;
                }

                if (!str.startsWith("POST /"))
                    throw new Exception("仅支持GET/POST请求");

                String action = str.split(" ")[1].split("\\?")[0].substring(1);
                str = str.toLowerCase().split("content-length:")[1];

                if (null == str)
                    throw new Exception("POST请求必须提供Content-Length");

                int body_size = Integer.parseInt(str.split("\n")[0].trim(), 10);

                if (0 == body_size)
                    throw new Exception("POST请求Content-Length不能是0");

                byte[] body = new byte[body_size];
                // 复制多读出的 body
                System.arraycopy(bytes, i, body, 0, body_index);
                body_size -= body_index;
                //noinspection UnusedAssignment
                bytes = null;

                if (body_size > 0) {
                    bytes = new byte[100];
                    // 只有没有读全时才需要继续读取
                    while (body_size > 0 && -1 != (len = is.read(bytes))) {
                        System.arraycopy(bytes, 0, body, body_index, len);
                        body_size -= len;
                        body_index += len;
                    }

                    //noinspection UnusedAssignment
                    bytes = null;
                }

                // 这个逻辑,可能属于重复 byte 转 str
                str = new String(body);
                //noinspection UnusedAssignment
                body = null;
                UrlQuerySanitizer query = new UrlQuerySanitizer();
                query.setAllowUnregisteredParamaters(true);// 支持_划线
                query.parseQuery(str);
                switch (action) {
                    case "play_pause_toggle":
                        player_toggle_pause();
                        http_response(client.getOutputStream(), "操作已发送");
                        break;
                    case "seek":
                        player_seek(Integer.parseInt(query.getValue("seek")) * 1000 * 60L);
                        http_response(client.getOutputStream(), "操作已完成");
                        break;
                    case "send_url":
                        // 播放远程视频 或 捕捉页面中视频链接
                        send_url(query.getValue("url"), query.getValue("seek"));
                        http_response(client.getOutputStream(), "操作收到,注意关注屏幕提示");
                        break;
                    default:
                        throw new Exception("该操作未实现");
                }
            } catch (Exception e) {
                e.printStackTrace();
                http_response(client.getOutputStream(), e.getMessage());
            }
        } catch (Exception e) {
            set_tip("建立socket失败:" + e.getMessage());
            e.printStackTrace();
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

    private void get_resource(OutputStream os, String str) throws Exception {
        str = str.split(" ")[1].split("\\?")[0].substring(1);
        String mime = "text/html";

        if (str.isEmpty()) {
            str = "index.html";
        } else if (str.endsWith(".js")) {
            mime = "application/x-javascript";
        } else if (str.endsWith(".css")) {
            mime = "text/css";
        }

        str = "/res/raw/" + str;

        try (InputStream is = R.raw.class.getResourceAsStream(str)) {
            if (null == is)
                throw new Exception("无效资源 " + str);
            // 200k缓存
            byte[] body = new byte[1024 * 200];
            int body_len = 0, read;

            while ((read = is.read(body, body_len, 1024)) > -1) {
                body_len += read;
            }

            os.write((
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: " + mime + ";charset=utf-8\r\n"
                            + "Content-Length: " + body_len + "\r\n"
                            + "\r\n"
            ).getBytes());

            if (body_len > 0)
                os.write(body);
        }
    }

    private void has_net() {
        if (PackageManager.PERMISSION_DENIED == checkCallingOrSelfPermission(Manifest.permission.INTERNET))
            set_tip("请授予网络权限");
    }

    private void http_response(OutputStream os, String body) {
        try {
            if (null == body) body = "不明错误";
            os.write((
                    "HTTP/1.1 200 OK\r\n"
                            + "Allow: PUT, GET, POST, OPTIONS\r\n"
                            + "Content-Type: text/html;charset=utf-8\r\n"
                            + "Access-Control-Allow-Origin: *\r\n"
                            + "Access-Control-Allow-Headers: *\r\n"
                            + "Content-Length: " + body.getBytes().length + "\r\n"
                            + "\r\n"
                            + body
            ).getBytes());
        } catch (Exception e) {
            set_tip("应答失败:" + e.getMessage());
        }
    }

    private void destroy_http() {
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 如果处于二维码界面,结束回收资源,若需要再次启动即可
        if (null == simpleExoPlayer) {
            Log.d("qidizi_debug", "切换到后台,结束 " + this.getClass().getName());
            finish();
        }
    }

    private void send_url(String url, String seek) {
        String path = url.replace("[?#].*$", "").toLowerCase();

        // 注意它要求必须是正则匹配整个字符,而不仅仅是开始
        if (!path.matches("^[^:]+:/+.+")) {
            set_tip("无效链接：必须有协议 " + path);
            return;
        }

        if (path.matches(".+\\.(mp4|m3u8|mpd)$")) {
            set_tip("即将播放视频...");
            play_url(url, seek);
            return;
        }

        if (!path.matches(".+\\.\\w+$") || path.matches(".+\\.(html?|php|aspx?|jsp)$")) {
            get_video_url(url, seek);
            return;
        }

        set_tip("无效链接，类型不支持 " + path);
    }

    private void play_url(final String url, final String seek_min) {
        final MainActivity self = this;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                has_net();
                int seek = 0;

                if (null != seek_min) {
                    try {
                        seek = Integer.parseInt(seek_min) * 60 * 1000;
                    } catch (Exception ignore) {
                    }
                }

                if (null == url) return;

                DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(self,
                        Util.getUserAgent(self,
                                "Mozilla/5.0 (Linux; Android 8.0; Pixel 2 Build/OPD3.170816.012)" +
                                        " AppleWebKit/537.36 (KHTML, like Gecko)" +
                                        " Chrome/84.0.4147.89 Mobile Safari/537.36"
                        ));
                MediaSource videoSource;
                Uri uri = Uri.parse(url);
                MediaItem mi = MediaItem.fromUri(uri);
                @C.ContentType int type = Util.inferContentType(uri, url);
                switch (type) {
                    case C.TYPE_DASH:
                        videoSource = new DashMediaSource.Factory(dataSourceFactory).createMediaSource(mi);
                        break;
                    case C.TYPE_SS:
                        videoSource = new SsMediaSource.Factory(dataSourceFactory).createMediaSource(mi);
                        break;
                    case C.TYPE_HLS:
                        videoSource = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mi);
                        break;
                    case C.TYPE_OTHER:
                        videoSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mi);
                        break;
                    default:
                        set_tip("Unsupported type: " + type);
                        return;
                }

                simpleExoPlayer.setMediaSource(videoSource);
                simpleExoPlayer.prepare();
                // 自动播放
                simpleExoPlayer.setPlayWhenReady(true);
                if (seek > 0)
                    simpleExoPlayer.seekTo(seek);
                set_tip("尝试播放:" + url);
            }
        });
    }

    private String get_ipv4() throws SocketException {
        for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
            NetworkInterface nif = en.nextElement();
            for (Enumeration<InetAddress> enumIpAddr = nif.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                InetAddress inetAddress = enumIpAddr.nextElement();
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

        return null;
    }

    private void info(String msg) {
        Log.i("tv_ime", msg);
    }

    private void show_qr(boolean show) {
        try {
            if (null == qr_view) return;

            if (!show) {
                // 隐藏
                qr_view.setVisibility(TextView.INVISIBLE);
                return;
            }

            String ip = get_ipv4();
            info("本机ip:" + ip);

            if (null == ip) {
                set_tip(String.format("无法获取本机的ip,请在手机通过浏览器使用 http://本机ip:%s/ 来访问", PORT));
                return;
            }

            int size = dp2px(dip_font_size * 4);
            BitMatrix result = new QRCodeWriter().encode(
                    String.format(
                            "http://%s:%s/?rnd=%s",
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
            final String txt = "扫码遥控\n" + ip + "\n" + qr_placeholder;
            SpannableString spannableString = new SpannableString(txt);
            spannableString.setSpan(
                    imageSpan,
                    txt.length() - qr_placeholder.length(),
                    txt.length(),
                    SpannableString.SPAN_COMPOSING
            );
            qr_view.setText(spannableString);
            qr_view.setVisibility(TextView.VISIBLE);
        } catch (Exception e) {
            set_tip("创建二维码失败:" + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (null != simpleExoPlayer)
            simpleExoPlayer.setPlayWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // 正在播放就暂停
                player_toggle_pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 快退
                player_seek(simpleExoPlayer.getCurrentPosition() - seek_step);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 快进
                player_seek(simpleExoPlayer.getCurrentPosition() + seek_step);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                // 上个视频/回到0
                simpleExoPlayer.previous();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 下个视频 一般没有效果
                simpleExoPlayer.next();
                return true;
        }
        return false;
    }

    private void player_toggle_pause() {
        if (null == simpleExoPlayer) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                simpleExoPlayer.setPlayWhenReady(!simpleExoPlayer.getPlayWhenReady());
            }
        });
    }

    private void player_seek(final long seek) {
        if (null == simpleExoPlayer) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                simpleExoPlayer.seekTo(seek);
            }
        });
    }

    private void destroy_player() {
        if (simpleExoPlayer == null) return;
        simpleExoPlayer.release();
        simpleExoPlayer = null;
    }

    @Override
    public void onDestroy() {
        destroy_player();
        destroy_http();
        super.onDestroy();
    }
}

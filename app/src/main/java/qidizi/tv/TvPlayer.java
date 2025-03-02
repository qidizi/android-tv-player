package qidizi.tv;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;

public class TvPlayer {
    protected String videoUrl = "";
    protected int playbackState = 0;
    protected int playerErrorCode = 0;
    protected long durationMs = 0;
    protected long positionMs = 0;
    private final TextView playerStateView;
    private final ExoPlayer exoPlayer;
    private final MainActivity activity;
    private final Handler secHandler = new Handler(Looper.getMainLooper());
    private final Runnable secRunnable = new Runnable() {
        @Override
        public void run() {
            secHandler.postDelayed(this, 1000);// 固定1秒执行一次
            // 播放器本身不提供定时刷新，需要自行秒级刷新播放进度
            positionMs = exoPlayer.getCurrentPosition();
        }
    };

    protected TvPlayer(MainActivity activity) {
        this.activity = activity;
        playerStateView = activity.findViewById(R.id.playerStateView);
        // 创建播放器
        exoPlayer = new androidx.media3.exoplayer.ExoPlayer.Builder(activity).build();
        create();
    }

    private void create() {
        playerStateView.setTextSize(activity.baseTextSizeSp);
        // 自定义播放界面
        // https://developer.android.google.cn/media/media3/ui/customization?hl=zh-cn
        exoPlayer.addListener(new Player.Listener() {

            @Override
            public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
                Player.Listener.super.onEvents(player, events);
                debugEvents(events);

                if (events.contains(Player.EVENT_PLAYER_ERROR)) {
                    PlaybackException error = player.getPlayerError();
                    if (null != error) {
                        // 注意考虑清0时机
                        playerErrorCode = error.errorCode;
                        String videoUrl = getVideoUrl();
                        activity.tvToast.push(String.format(Locale.US, "无法播放%s：%s(%d)", videoUrl, Util.getExceptionMessage(error), error.errorCode));
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
                        activity.qr.hide();
                        playerStateView.setVisibility(View.GONE);
                        activity.tvToast.hide();
                    } else {
                        // 非播放状态
                        // 立刻先保存播放进度，以确保变成暂停是同步的
                        positionMs = exoPlayer.getCurrentPosition();
                        activity.qr.show();
                        playerStateView.setVisibility(View.VISIBLE);
                        // 无脑显示，如果内容空，是透明的
                        activity.tvToast.show();
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
        ((PlayerView) activity.findViewById(R.id.videoView)).setPlayer(exoPlayer);
        secRunnable.run();
    }

    protected void onKeyDown(int keyCode) {
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
        }
    }

    protected void onTouchEvent(MotionEvent event) {

        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            // 抬起手指时处理，方便手机调试
            if (exoPlayer.isPlaying()) {
                pause();
            } else {
                play();
            }
        }
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

    private void renderPlayerState() {
        playerStateView.setText(String.format("%s (%s/%s)", stateHuman(playbackState), Util.msHuman(positionMs), Util.msHuman(durationMs)));
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

    @OptIn(markerClass = UnstableApi.class)
    protected void playUrl(String url, String userAgent, String referer, String httpProxyHost, String httpProxyPort) throws Exception {
        // 重置某些信息
        videoUrl = url;
        playerErrorCode = 0;
        durationMs = 0;
        positionMs = 0;
        try {
            final MediaSource mediaSource = createMediaSource(
                    url,
                    userAgent,
                    referer,
                    httpProxyHost,
                    httpProxyPort
            );
            // 方法必须在main线程
            activity.runOnUiThread(() -> {
                exoPlayer.setMediaSource(mediaSource);
                exoPlayer.prepare();
                exoPlayer.play();
            });
        } catch (Exception e) {
            Util.debugException(e, "播放url");
            throw new Exception(url + " 播放失败: " + Util.getExceptionMessage(e));
        }
    }

    protected void play() {
        // 必须在ui线程播放;
        // todo 如果状态是已播放完，就算seek后再放也没效果？
        activity.runOnUiThread(exoPlayer::play);
    }

    protected void pause() {
        // 必须在ui线程播放
        activity.runOnUiThread(exoPlayer::pause);
    }

    protected void seek(int seekMinutes) {
        activity.runOnUiThread(() -> exoPlayer.seekTo((long) seekMinutes * 60 * 1000));
    }

    @OptIn(markerClass = UnstableApi.class)
    private MediaSource createMediaSource(
            String url,
            String userAgent,
            String referer,
            String httpProxyHost,
            String httpProxyPort
    ) throws Exception {
        if (null == url || url.isEmpty()) throw new Exception("url 为空");
        if (null == referer || referer.isEmpty()) referer = url;
        if (null == userAgent || userAgent.isEmpty())
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0";
        // 创建 MediaItem
        MediaItem mediaItem = MediaItem.fromUri(url);
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
        // 换用默认http，以支持android 4.0
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        int port = Util.getVerifyHttpProxyPort(httpProxyHost, httpProxyPort);
        if (port > 0)
            clientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpProxyHost, port)));

        return new DefaultMediaSourceFactory(
                new OkHttpDataSource.Factory(clientBuilder.build())
                        .setUserAgent(userAgent)
                        .setDefaultRequestProperties(Map.of("Referer", referer))
        ).createMediaSource(mediaItem);
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


    // 用于了解player事件发生顺序及包含信息，正式时不需要打开
    private void debugEvents(Player.Events events) {
        if (!Util.debug) return;

        StringBuilder en = new StringBuilder();

        for (int i = 0; i < events.size(); i++) {
            en.append('\n').append(getPlayerEventDesc(events.get(i)));
        }
        Util.consoleDebug("onEvents:%s", en.toString());
    }

    protected void destroy() {
        if (null != exoPlayer) {
            activity.runOnUiThread(exoPlayer::stop);
            exoPlayer.release();
        }
    }
}

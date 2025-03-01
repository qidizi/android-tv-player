package qidizi.tv;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

public class Volume {
    private static boolean isMuted = false;

    protected static String adjust(Activity activity, String how) throws Exception {
        // 目标是调整系统全局音量，而不是本播放器
        if (null == how)
            how = "unknown";
        // 经过测试在mi9 android 11系统中，切换到其它应用窗口（本app窗口在背后了），这个调整没有效果；
        // 目前普通权限app暂未找到解决方案
        AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        switch (how) {
            case "0":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // android 6.0+
                    audioManager.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                } else {
                    // 低于 Android 6.0 的版本
                    audioManager.setStreamMute(AudioManager.STREAM_MUSIC, !isMuted);
                    isMuted = !isMuted;
                }
                return "静音切换";
            case "1":
                audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                return "增加音量";
            case "-1":
                audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                return "减少音量";
        }

        throw new Exception("未实现的音量操作：" + how);
    }
}

package qidizi.tv;

import android.app.Activity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;

// https://stackoverflow.com/questions/21814825/you-need-to-use-a-theme-appcompat-theme-or-descendant-with-this-activity

public class MainActivity extends Activity {
    protected Qr qr;
    protected TvServer tvServer;
    protected TvPlayer tvPlayer;
    protected TvToast tvToast;

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
            StaticData.baseTextSizeSp *= 3;
        }

        try {
            StaticData.tvIp = TvIp.get();
        } catch (Exception e) {
            Util.debugException(e, "获取电视ip");
            tvToast.push("获取电视lan ip失败：" + Util.getExceptionMessage(e));
            return;
        }

        tvToast = new TvToast(this);
        tvPlayer = new TvPlayer(this);
        tvServer = new TvServer(this);
        qr = new Qr(this);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        tvPlayer.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        tvPlayer.pause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (KeyEvent.KEYCODE_BACK == keyCode) {
            // 忽略返回按键,防止播放中不小心按
            return true;
        }
        tvPlayer.onKeyDown(keyCode);
        // 忽略返回按键,防止)
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        // 注意顺序
        tvServer.destroy();
        tvPlayer.destroy();
        tvToast.destroy();
        super.onDestroy();
    }
}

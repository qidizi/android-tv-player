package qidizi.tv;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class MainActivity extends Activity {
    final private static int tipId = 100000;
    final private static int qrCodeId = tipId + 1;
    private static MainActivity mainActivity = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 隐藏标题
        this.getActionBar().hide();
        // 隐藏标题，这个方案好像有时有问题，在小米9上
        // this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        // 隐藏状态行
        //this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        mainActivity = this;
        // 创建qr code 图片容器
        ImageView imageView = new ImageView(this);
        imageView.setId(qrCodeId);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER
        );
        this.setContentView(imageView, layout);
        // 创建提示view
        TextView textView = new TextView(this);
        textView.setGravity(Gravity.START);
        layout = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER
        );
        addContentView(textView, layout);
        textView.setId(tipId);
        textView.setBackgroundColor(Color.GRAY);
        textView.setTextColor(Color.BLACK);
        textView.setPadding(10, 10, 10, 10);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 40);
        textView.setClickable(true);
        // 只能上下滚动,true 会变成左右滚动
        textView.setHorizontallyScrolling(false);
        textView.setFocusable(true);
        textView.setMovementMethod(new ScrollingMovementMethod());
    }

    private void refreshLayout() {
        ImageView imageView = findViewById(qrCodeId);
        TextView textView = findViewById(tipId);

        if (null == imageView || null == textView) {
            MyApplication.toast("二维码及提示框不存在");
            return;
        }

        // 目前小米手机的悬浮窗口获取的大小并不准确，且退出悬浮没有触发
        DisplayMetrics metrics = this.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        FrameLayout.LayoutParams layout;

        if (width < height) {
            // 如果是高屏/正方形，上下布局；
            int minSize = Math.min(height / 2, width);
            layout = new FrameLayout.LayoutParams(
                    minSize,
                    minSize,
                    Gravity.BOTTOM | Gravity.CENTER
            );
            imageView.setLayoutParams(layout);
            layout = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    height / 2,
                    Gravity.TOP | Gravity.CENTER
            );
        } else {
            // 如果是宽屏，左右
            int minSize = Math.min(width / 2, height);
            layout = new FrameLayout.LayoutParams(
                    minSize,
                    minSize,
                    Gravity.START | Gravity.CENTER
            );
            imageView.setLayoutParams(layout);
            layout = new FrameLayout.LayoutParams(
                    width / 2,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.END | Gravity.CENTER
            );
        }
        textView.setLayoutParams(layout);
    }

    private void refreshQr() {
        String url = MyApplication.getHttpUrl();
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
            ((ImageView) findViewById(qrCodeId)).setImageBitmap(bmp);
            tip("请扫码访问 " + url);
        } catch (Exception e) {
            e.printStackTrace();
            tip("创建二维码失败:" + e.getMessage());
        }
    }

    private void tip(String msg) {
        try {
            ((TextView) findViewById(tipId)).setText(msg);
        } catch (Exception e) {
            e.printStackTrace();
            // 如果在activity中显示失败，就换这个方式
            MyApplication.toast(msg);
        }
    }

    @Override
    protected void onResume() {
        refreshQr();
        // 这个不能放到 super.onResume 后面，否则会导致出错
        // android.os.BinderProxy cannot be cast to android.app.servertransaction.ClientTransaction
        // 放在这里，小米 9 悬浮窗口退出都无法刷新
        // refreshLayout();
        super.onResume();
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
        // 放在这，目前小米 9 切成悬浮布局异常
        refreshLayout();
        super.onWindowAttributesChanged(params);
    }

    @Override
    protected void onDestroy() {
        mainActivity = null;
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MyApplication.setInBackground(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MyApplication.setInBackground(true);
    }

    public static MainActivity getMe() {
        return mainActivity;
    }
}

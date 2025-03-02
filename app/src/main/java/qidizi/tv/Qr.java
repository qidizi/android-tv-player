package qidizi.tv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.os.Handler;
import android.view.Display;
import android.widget.ImageView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

public class Qr {
    protected int qrSize = 0;
    private final ImageView qrCodeImageView;
    private final MainActivity activity;

    protected Qr(MainActivity activity) {
        this.activity = activity;
        this.qrCodeImageView = activity.findViewById(R.id.qrCodeImageView);
        create(activity);
    }

    private void create(MainActivity activity) {
        // 绘制二维码
        Display display = activity.getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);
        qrSize = Math.min(point.x, point.y) / 4;
        // 应用 LayoutParams 到二维码图像视图
        qrCodeImageView.getLayoutParams().width = qrCodeImageView.getLayoutParams().height = qrSize;
        drawQr();
    }

    protected void show() {
        qrCodeImageView.setVisibility(ImageView.VISIBLE);
    }

    protected void hide() {
        qrCodeImageView.setVisibility(ImageView.GONE);
    }

    private void drawQr() {
        if (null == activity.tvIp || "0.0.0.0".equals(activity.tvIp)) {
            // 未能获取到ip，定时重试;若已取得，断线将不再重试
            Bitmap bitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.LTGRAY);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.RED);
            String text = "取IP中";
            // 简单用size计算文字大小,内容少，不用那么准，能看清字即可
            paint.setTextSize(qrSize * 0.9f / text.length());
            paint.setTextAlign(Paint.Align.CENTER);
            float x = (float) bitmap.getWidth() / 2;
            float y = ((float) bitmap.getHeight() / 2) - ((paint.descent() + paint.ascent()) / 2);
            canvas.drawText(text, x, y, paint);
            qrCodeImageView.setImageBitmap(bitmap);
            int delaySec = 3;
            new Handler().postDelayed(() -> {
                activity.getTvIp();
                drawQr();
            }, delaySec * 1000);
            activity.tvToast.push("未能取得电视局域网的IP，" + delaySec + "秒后重试...");
            return;
        }

        String content = "http://" + activity.tvIp + ":" + activity.tvServer.server_port + "/?r=" + System.currentTimeMillis();
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
            activity.tvToast.push("欢迎使用" + activity.getString(R.string.app_name) + "！\n请用微信/浏览器扫码播放视频/控制");
        } catch (Exception e) {
            Util.debugException(e, "创建二维码时");
            activity.tvToast.push("创建二维码失败：" + Util.getExceptionMessage(e));
        }
    }
}

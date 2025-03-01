package qidizi.tv;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.view.Display;
import android.widget.ImageView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

public class Qr {
    private final ImageView qrCodeImageView;

    protected Qr(MainActivity activity) {
        this.qrCodeImageView = activity.findViewById(R.id.qrCodeImageView);
        create(activity);
    }

    private void create(MainActivity activity) {
        // 绘制二维码
        Display display = activity.getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);

        int size = StaticData.qrSize = Math.min(point.x, point.y) / 4;
        // 应用 LayoutParams 到二维码图像视图
        qrCodeImageView.getLayoutParams().width = qrCodeImageView.getLayoutParams().height = size;

        String content = "http://" + StaticData.tvIp + ":" + StaticData.server_port + "/?r=" + System.currentTimeMillis();
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8"); // 设置字符编码
        hints.put(EncodeHintType.MARGIN, 1); // 设置二维码边距

        try {
            // 生成二维码矩阵
            BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            // 将 BitMatrix 转换为 Bitmap
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
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

    protected void show() {
        qrCodeImageView.setVisibility(ImageView.VISIBLE);
    }

    protected void hide() {
        qrCodeImageView.setVisibility(ImageView.GONE);
    }
}

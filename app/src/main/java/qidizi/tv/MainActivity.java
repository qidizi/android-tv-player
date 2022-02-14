package qidizi.tv;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Display;
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
import java.util.Enumeration;
import java.util.regex.Pattern;
import android.widget.*;
import android.text.method.*;
import android.os.*;
import android.content.*;

public class MainActivity extends Activity
{
    final private static int tipId = 100000;
	private static MainActivity mainActivity = null;

    @Override
    protected void onCreate(Bundle savedInstanceState)
	{
        super.onCreate(savedInstanceState);
		mainActivity = this;
    }

    private void createQr()
	{
		String url;
		try
		{
			url = MyApplication.getHttpUrl();	
		}
		catch (Exception e)
		{
			tip("获取ip失败：" + e.getMessage());
			return;
		}
        Display display = getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);
        // 只使用80%，剩下的给提示使用
        int minSize = (int) (Math.min(point.x, point.y) * 0.8);
        // 创建二维码对象

        QRCodeWriter writer = new QRCodeWriter();
        try
		{

            BitMatrix bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, minSize, minSize);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++)
			{
                for (int y = 0; y < height; y++)
				{
                    bmp.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(bmp);
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT,
				// 上中
				Gravity.BOTTOM | Gravity.CENTER
            );
            this.setContentView(imageView, layout);
            tip("请扫码访问 " + url);
        }
		catch (Exception e)
		{
			e.printStackTrace();
            tip("创建二维码失败:" + e.getMessage());            
        }
    }

    private void tip(String msg)
	{
        try
		{
            TextView textView;

            if (null == findViewById(tipId))
			{
                // 未存在，先创建
                Display display = getWindowManager().getDefaultDisplay();
                Point point = new Point();
                display.getSize(point);
                // 只使用20%，剩下的给qrCode使用
                int height = (int) (point.y * 0.2);
                textView = new TextView(this);
                textView.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
					FrameLayout.LayoutParams.MATCH_PARENT,
					height,
					Gravity.TOP | Gravity.CENTER
                );
                addContentView(textView, layout);
                textView.setId(tipId);
                textView.setBackgroundColor(Color.WHITE);
                textView.setTextColor(Color.BLACK);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 50);				
				textView.setClickable(true);
				textView.setHorizontallyScrolling(true);
				textView.setFocusable(true);
				textView.setMovementMethod(new ScrollingMovementMethod());
				textView.setScrollbarFadingEnabled(false);				
            }
			else
			{
                textView = findViewById(tipId);
            }

            if (null == textView) throw new Exception("创建textViewj失败");
            textView.setText(msg);
        }
		catch (Exception ignore)
		{
			ignore.printStackTrace();
            // 如果在activity中显示失败，就换这个方式
            MyApplication.toast(msg);
        }
    }

    @Override
    protected void onStart()
	{
        // TODO: Implement this method
        super.onStart();
        createQr();
    }

    private void toastAndTip(String msg)
	{
        MyApplication.toast("请切到app查看新消息");
        tip(msg);
    }

	@Override
	protected void onDestroy()
	{
		mainActivity = null;
		// TODO: Implement this method
		super.onDestroy();
	}	
	
	public static MainActivity getMe(){
		return mainActivity;
	}
}

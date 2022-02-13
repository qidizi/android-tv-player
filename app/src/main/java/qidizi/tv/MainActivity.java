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
import android.text.SpannableString;
import android.text.style.ImageSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.InputStream;
import java.net.*;
import java.util.Enumeration;
import com.google.zxing.*;
import android.widget.*;
import android.widget.RelativeLayout.*;
import android.os.*;
import android.content.*;
import java.util.regex.*;

public class MainActivity extends Activity
{
    // ~/android/platform-tools/adb forward tcp:8080 tcp:8080 来影射avd中的端口到物理机
    // 一般的系统是不允许普通应用开启1024以下的端口的
    private final static int PORT = 8080;
	final private Handler handler = new Handler();
	// activity 生命周期  https://developer.android.google.cn/guide/components/activities/activity-lifecycle?hl=zh-cn
	private ServerSocket http;

    @Override
    protected void onCreate(Bundle savedInstanceState)
	{
        super.onCreate(savedInstanceState);
		//longTip("onCreate");
        hasNet();
		createHttp();
    }

    private void createQr()
	{
        Display display = getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);
        int minSize = Math.min(point.x, point.y);
        // 创建二维码对象

		QRCodeWriter writer = new QRCodeWriter();
		try
		{
			String ip = getIpv4();
		    ip = String.format(
				// 防止idea报换https
				"http" + "://%s:%s/?noCache=%s",
				ip,
				PORT,
				System.currentTimeMillis()
			);
			BitMatrix bitMatrix = writer.encode(ip, BarcodeFormat.QR_CODE, minSize, minSize);
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
				Gravity.TOP | Gravity.CENTER
			);

			this.setContentView(imageView, layout);
            longTip("请扫码访问 " + ip + ":" + PORT);
        }
		catch (Exception e)
		{
            longTip("创建二维码失败:" + e.getMessage());
            finish();
        }
    }

	@Override
	protected void onDestroy()
	{
		// TODO: Implement this method
		super.onDestroy();
		//longTip("onDestroy");
	}

	@Override
	protected void onStop()
	{
		// TODO: Implement this method
		super.onStop();
		//longTip("onStop");
	}

	@Override
	protected void onResume()
	{
		// TODO: Implement this method
		super.onResume();
		//longTip("onResume");
	}

	@Override
	protected void onStart()
	{
		// TODO: Implement this method
		super.onStart();
        createQr();
	}


	@Override
	protected void onRestart()
	{
		// TODO: Implement this method
		super.onRestart();
		//longTip("onRestart");
	}

	@Override
	protected void onPause()
	{
		// TODO: Implement this method 
		super.onPause();
		//longTip("onPause");
	}

    private void createHttp()
	{
		if (null != http)
		{
			longTip("http服务已运行");
			return;
		}

        new Thread(new Runnable() {
				@Override
				public void run()
				{
					try
					{					
						// 使用端口转发功能,把虚拟机avd端口转发到开发机的8080上,就可以使用 http://127.0.0.1:8080 来访问
						// ~/android/platform-tools/adb forward tcp:8080 tcp:8080
						http = new ServerSocket(PORT, 1);
						http.setReuseAddress(true);

						if (!http.isBound())
							throw new Exception("无法绑定端口 " + PORT);

						//noinspection
						while (!http.isClosed())
							accept(http);
					}
					catch (Exception e)
					{
						longTip("电视端启动失败,请重启本应用试试:" + e.getMessage());
						finish();
					}
				}
			}).start();
    }

    private void accept(ServerSocket http)
	{
        Socket client = null;
        InputStream is = null;

        try
		{
            hasNet();
            client = http.accept();
            is = client.getInputStream();
            try
			{
                client.setSoTimeout(1000 * 3);
                int maxGet = 1024 * 1024;
                // get 支持的长度: 1M ?放多一位来放\0
                byte[] bytes = new byte[maxGet + 1];
                // 找到 rn rn标志
                int i = is.read(bytes, 0, maxGet);
                // 取header
                String str = new String(bytes, 0, i - 1);
                // 释放内存
                //noinspection UnusedAssignment
                bytes = null;

                if (!Pattern.matches("^GET [^ ]+ HTTP/\\S+[\r\n][\\s\\S]*$", str))
                    throw new Exception("非法GET请求\n正确格式：/?a=b&url=...\n当前请求：" + str);

			    str = str.split(" ", 3)[1];	
				Uri uri = Uri.parse(str);
				String videoUrl = uri.getQueryParameter("url");
				String msg ="";

				if (null == videoUrl || videoUrl.isEmpty())
				{
					msg = "请提供querystring：&url=视频网址\n" + str;
				}
				else
				{
					try{
						playUrl(videoUrl);
					}catch(Exception e){
						msg = "操作url失败：" + e.getMessage();
					}
				}


                String html = "/res/raw/index.html";

                try (InputStream htmlIs = R.raw.class.getResourceAsStream(html)) {
                    if (null == htmlIs)
					{
                        longTip("无效资源 " + html);
                        finish();
                        return;
                    }

                    // 200k缓存
                    byte[] body = new byte[1024 * 200];
                    int body_len = 0, read;

                    while ((read = htmlIs.read(body, body_len, 1024)) > -1)
					{
                        body_len += read;
                    }

					msg = msg.replaceAll("\"", "&quot;");
					body = new String(body).replaceFirst("\\{msg\\}", msg).getBytes();
                    client.getOutputStream().write(
						(
						"HTTP/1.1 200 OK\r\n"
						+ "Content-Type: text/html;charset=utf-8\r\n"
						+ "Content-Length: " + body.length + "\r\n"
						+ "\r\n"
						).getBytes()
					);

                    if (body_len > 0)
                        client.getOutputStream().write(body);
                }
            }
			catch (Exception e)
			{
                longTip("读取请求失败：" + e.getMessage());
            }
        }
		catch (Exception e)
		{
            longTip("建立socket失败:" + e.getMessage());
            finish();
        }
		finally
		{
            if (is != null)
			{
                try
				{
                    is.close();
                }
				catch (Exception ignore)
				{

                }
            }

            if (client != null)
			{
                try
				{
                    client.close();
                }
				catch (Exception ignore)
				{

                }
            }
        }
    }

    private void hasNet()
	{
        if (PackageManager.PERMISSION_DENIED == checkCallingOrSelfPermission(Manifest.permission.INTERNET))
		{
            longTip("请授予本应用网络权限再试");
            finish();
        }
    }

    private void longTip(final String msg)
	{
		//final Context ui = this.;
		handler.post(new Runnable() {
				@Override
				public void run()
				{
					// 确保操作处于ui线程
					Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
				}
			});        
    }

    private void playUrl(final String url)
	{
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
			.setPrimaryClip(ClipData.newPlainText("video", url));
        Uri uri = Uri.parse(url);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/*");

        if (intent.resolveActivity(getPackageManager()) != null)
		{
            startActivity(intent);
        }
		else
		{
            longTip("无法调起外围视频播放器");
            return;
        }

        longTip("已经复制到剪切板，及向系统发起外围播放器播放：\n" + url);
    }

    private String getIpv4()
	{
        try
		{
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();)
			{
                NetworkInterface nif = en.nextElement();
                for (Enumeration<InetAddress> enumIps = nif.getInetAddresses(); enumIps.hasMoreElements();)
				{
                    InetAddress inetAddress = enumIps.nextElement();
                    if (!inetAddress.isLoopbackAddress() && (inetAddress instanceof Inet4Address))
					{
                        String ip = inetAddress.getHostAddress();
                        if (ip.startsWith("10.") || ip.startsWith("192.168."))
						{
                            return ip;
                        }

                        if (ip.startsWith("172."))
						{
                            int sub = Integer.parseInt(ip.split("\\.")[1]);

                            if (sub >= 16 && sub <= 31)
                                return ip;
                        }
                    }
                }
            }
        }
		catch (Exception e)
		{
            longTip("获取ip失败：\n\n" + e.getMessage());
            finish();
        }
        return null;

    }
}

package qidizi.tv;

import android.Manifest;
import android.app.Application;
import android.content.ClipData;
// 确保 import 的是 android.content.ClipboardManager
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.widget.Toast;

import java.io.InputStream;
import java.net.*;
import java.util.Enumeration;
import java.util.regex.Pattern;

public class MyApplication extends Application
{
    // ~/android/platform-tools/adb forward tcp:8080 tcp:8080 来影射avd中的端口到物理机
    // 一般的系统是不允许普通应用开启1024以下的端口的
    private final static int PORT = 8080;
    // activity 生命周期  https://developer.android.google.cn/guide/components/activities/activity-lifecycle?hl=zh-cn
    private static ServerSocket http;
    private static MyApplication application;
    private boolean inBackground = false;

    @Override
    public void onCreate()
	{
        super.onCreate();
        application = this;
        createHttp();
    }

    private void createHttp()
	{
        if (null != http)
		{
            toast("http服务已存在");
            return;
        }

		// aide 不支持 Lambda
        new Thread(new Runnable() {
				@Override
				public void run()
				{
					try
					{
						// 使用端口转发功能,把虚拟机avd端口转发到开发机的8080上,就可以使用 http://127.0.0.1:8080 来访问
						// ~/android/platform-tools/adb forward tcp:8080 tcp:8080
						// 同时只允许一个请求,其它会被拒绝
						int maxConnections = 1;
						http = new ServerSocket(PORT, maxConnections);
						http.setReuseAddress(true);
					}
					catch (Exception e)
					{
						httpClose();
						toast("http服务启动失败：" + e.getMessage());
					}

					if (!http.isBound())
					{
						toast(PORT + " 端口绑定失败");
						httpClose();
						return;
					}

					//noinspection
					while (!http.isClosed())
						accept(http);
				}}).start();


    }

    private void accept(ServerSocket http)
	{
        if (!application.hasNet()) return;
		//aide 不支持 try管理res
		Socket client=null;
		InputStream is =null;
        try
		{
			client = http.accept();
			is = client.getInputStream();
            client.setSoTimeout(1000 * 3);
            int maxGet = 1024 * 2;
            // get 支持的长度，一般的 requestString 并不需要太长
            byte[] bytes = new byte[maxGet];
            int i = is.read(bytes, 0, maxGet);
            // 有时读取可能小于分配长度
            String str = new String(bytes, 0, i - 1);
            // 释放内存
            //noinspection UnusedAssignment
            bytes = null;

            if (!Pattern.matches("^GET [^ ]+ HTTP/\\S+[\r\n][\\s\\S]*$", str))
                throw new Exception("非法GET请求：" + str);

            str = str.split(" ", 3)[1];
            Uri uri = Uri.parse(str);
            String videoUrl = uri.getQueryParameter("url");

            if (null != videoUrl && !videoUrl.isEmpty())
			{
                application.playUrl(videoUrl);
            }
			else if (str.contains("url="))
			{
                // 只有提交时才提醒
                toast("get请求缺失：&url=视频url");
            }

            String html = "/res/raw/index.html";
            try (InputStream htmlIs = R.raw.class.getResourceAsStream(html)) {
                if (null == htmlIs)
                    throw new Exception("无效资源 " + html);

                int bodyLen = 1024 * 50;
                byte[] body = new byte[bodyLen];
                bodyLen = htmlIs.read(body);
                client.getOutputStream().write(
					(
					"HTTP/1.1 200 OK\r\n"
					+ "Content-Type: text/html;charset=utf-8\r\n"
					+ "Content-Length: " + bodyLen + "\r\n"
					+ "\r\n"
					).getBytes()
                );

                if (bodyLen > 0)
                    client.getOutputStream().write(body);
            }
        }
		catch (Exception e)
		{
            e.printStackTrace();
            // 如果联网权限未得到，这里会报
            // android.system.ErrnoException: accept failed: EACCES (Permission denied)
            toast("处理请求失败:" + e.getMessage());
        }
		finally
		{
			try
			{
				if (is != null)is.close();
			}
			catch (Exception ignore)
			{}
			try
			{
				if (client != null)client.close();
			}
			catch (Exception ignore)
			{}
		}
    }

    static public String getHttpUrl()
	{
        String ip = null;
        Enumeration<NetworkInterface> en;

        try
		{
            en = NetworkInterface.getNetworkInterfaces();
        }
		catch (Exception e)
		{
            toast("获取电视ip失败：" + e.getMessage());
            return null;
        }

        while (en.hasMoreElements())
		{
            NetworkInterface nif = en.nextElement();
            for (Enumeration<InetAddress> enumIps = nif.getInetAddresses(); enumIps.hasMoreElements();)
			{
                InetAddress inetAddress = enumIps.nextElement();
                if (inetAddress.isLoopbackAddress() || !(inetAddress instanceof Inet4Address)) continue;
                String address = inetAddress.getHostAddress();
                if (address.startsWith("10.") || address.startsWith("192.168."))
				{
                    ip = address;
                    break;
                }

                if (address.startsWith("172."))
				{
                    int sub = Integer.parseInt(address.split("\\.")[1]);

                    if (sub >= 16 && sub <= 31)
					{
                        ip = address;
                        break;
                    }
                }
            }

            if (ip != null)
			{
                break;
            }
        }

        if (null == ip)
		{
            toast("推算电视的ip失败");
            return null;
        }

        return String.format(
			// 防止idea报换https
			"http" + "://%s:%s/?noCache=%s",
			ip,
			PORT,
			System.currentTimeMillis()
        );
    }

    private void playUrl(final String url)
	{
		final MainActivity mainActivity = MainActivity.getMe();

        if (null == mainActivity)
		{
			toast("activity为null");
			return;
		}

        mainActivity.runOnUiThread(new Runnable() {
				@Override
				public void run()
				{

					// 应用在后台时，无法操作剪切板
					// https://stackoverflow.com/questions/65632024/is-copy-to-clipboard-when-app-in-background-restricted-in-android
					ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					ClipData clip = ClipData.newPlainText("video", url);
					clipboard.setPrimaryClip(clip);

					Uri uri = Uri.parse(url);
					final Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setDataAndType(uri, "video/*");
					intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

					if (intent.resolveActivity(getPackageManager()) != null)
					{
						try
						{				
						    // 坚果投影 6.x系统要放到ui进程
							startActivity(intent);
						}
						catch (Exception e)
						{
							toast("startActivity异常：" + e.getMessage());
						}
					}
					else
					{
						toast("无法调起外围视频播放器");
						return;
					}

					toast((inBackground ? "" : "已复制至剪切板！") + "正送至外置播放器：" + url);

				}});
    }

    private boolean hasNet()
	{
        // 在小米 9，未授权访问网络，也返回了成功，但是首次会弹出授予网络的提示
        if (PackageManager.PERMISSION_DENIED == checkCallingOrSelfPermission(Manifest.permission.INTERNET))
		{
            toast("请授予本应用网络权限再试");
            return false;
        }

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm.getActiveNetworkInfo() == null || !cm.getActiveNetworkInfo().isConnected())
		{
            toast("请授予本应用网络权限再试");
            return false;
        }

        return true;
    }

    static public void toast(final String str)
	{
        final MainActivity mainActivity = MainActivity.getMe();

        if (null == mainActivity) return;
        mainActivity.runOnUiThread(new Runnable() {
				@Override
				public void run()
				{
					try
					{
						// 确保操作处于ui线程
						Toast.makeText(mainActivity, str, Toast.LENGTH_SHORT).show();
					}
					catch (Exception e)
					{
						System.out.printf("toast 内容：%s，异常如下", str);
						e.printStackTrace();
					}
				}});
    }

    static private void httpClose()
	{
        if (null != http && !http.isClosed())
		{
            long start = System.currentTimeMillis();
            try
			{
                http.close();
                http = null;
            }
			catch (Exception ignore)
			{

            }

            System.out.printf("关闭http耗时 %s 毫秒", System.currentTimeMillis() - start);
        }
    }

    static public void setInBackground(boolean b)
	{
        application.inBackground = b;
    }
}

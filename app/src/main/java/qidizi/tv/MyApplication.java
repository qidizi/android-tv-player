package qidizi.tv;
import android.app.*;
import android.os.*;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.io.*;
import java.util.regex.*;
import android.net.*;
import android.content.*;
import android.content.pm.*;
import android.*;

public class MyApplication extends Application
{
    // ~/android/platform-tools/adb forward tcp:8080 tcp:8080 来影射avd中的端口到物理机
    // 一般的系统是不允许普通应用开启1024以下的端口的
    private final static int PORT = 8080;
    // activity 生命周期  https://developer.android.google.cn/guide/components/activities/activity-lifecycle?hl=zh-cn
    private static ServerSocket http;	
	private static MyApplication application;

	@Override
	public void onCreate()
	{
		super.onCreate();
		application = this;        
	}

    public void createHttp()
	{
        if (null != http)
		{
            toast("http服务已存在");
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
						// 同时只允许一个请求,其它会被拒绝
						int maxConnections = 1;
						http = new ServerSocket(PORT, maxConnections);
						http.setReuseAddress(true);
					}
					catch (Exception e)
					{
						toast("http服务启动失败：" + e.getMessage());						
					}

					if (!http.isBound())
					{
						toast(PORT + " 端口绑定失败");
						return;
					}

					//noinspection
					while (!http.isClosed())
						accept(http);
				}
			}).start();
    }

    private void accept(ServerSocket http)
	{
        Socket client = null;
        InputStream is = null;

        if (!hasNet())
			return;
		try
		{
			client = http.accept();
            is = client.getInputStream();            
			client.setSoTimeout(1000 * 3);
			int maxGet = 1024;
			// get 支持的长度: 1M ?放多一位来放\0
			byte[] bytes = new byte[maxGet + 1];
			int i = is.read(bytes, 0, maxGet);
			String str = new String(bytes, 0, i - 1);
			// 释放内存
			//noinspection UnusedAssignment
			bytes = null;

			if (!Pattern.matches("^GET [^ ]+ HTTP/\\S+[\r\n][\\s\\S]*$", str))
				throw new Exception("非法GET请求：" + str);

			str = str.split(" ", 3)[1];
			Uri uri = Uri.parse(str);
			String videoUrl = uri.getQueryParameter("url");
			String msg = "";

			if (null == videoUrl || videoUrl.isEmpty())
			{
				msg = "请提供querystring：&url=视频网址\n" + str;
			}
			else
			{                    
				playUrl(videoUrl);
			}


			String html = "/res/raw/index.html";

			try (InputStream htmlIs = R.raw.class.getResourceAsStream(html)) {
				if (null == htmlIs)					
					throw new Exception("无效资源 " + html);                        

				int bodyLen = 1024 * 50;
				byte[] body = new byte[bodyLen];
				bodyLen = htmlIs.read(body);
				msg = msg.replaceAll("\"", "&quot;");
				body = new String(body, 0, bodyLen).replaceFirst("\\{msg\\}", msg).getBytes();
				bodyLen = body.length;
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
            toast("处理请求失败:" + e.getMessage());
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

	static public String getHttpUrl() throws Exception
	{
		String ip = null;
		InetAddress inet = http.getInetAddress();

		if (null == inet)
			throw new Exception("http未绑定");


		if (null == ip)
			throw new Exception("未能识别出内网ip");     
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
            toast("无法调起外围视频播放器");
            return;
        }

        toast("已经复制到剪切板，及向系统发起外围播放器播放：\n" + url);
    }

    private boolean hasNet()
	{
        if (PackageManager.PERMISSION_DENIED == checkCallingOrSelfPermission(Manifest.permission.INTERNET))
		{
            toast("请授予本应用网络权限再试");
			return false;
        }

		return true;
    }

	public static MyApplication getInstance()
	{
		return application;
	}

    static public void toast(final String str)
	{				
		final MainActivity mainActivity = MainActivity.getMe();

		if (null == mainActivity) return;
		mainActivity.	runOnUiThread(new Runnable() {
				@Override
				public void run()
				{
					try{
					// 确保操作处于ui线程
					Toast.makeText(mainActivity, str, Toast.LENGTH_SHORT).show();	
					}catch(Exception e){
						System.out.printf("toast 内容：%s，异常如下", str);
						e.printStackTrace();
					}
				}
			});								
    }
}

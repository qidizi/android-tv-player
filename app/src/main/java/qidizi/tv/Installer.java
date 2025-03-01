package qidizi.tv;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Installer {
    private final MainActivity mainActivity;

    public Installer(MainActivity mainActivity, final String apkUrl) {
        this.mainActivity = mainActivity;
        new Thread(() -> downloadApk(apkUrl)).start();
    }

    private void downloadApk(String apkUrl) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(apkUrl).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null)
                throw new IOException("无法下载文件: " + response.message());

            File apkFile = new File(mainActivity.getFilesDir(), "any.apk");
            try (InputStream inputStream = response.body().byteStream();
                 OutputStream outputStream = new FileOutputStream(apkFile)) {

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            mainActivity.tvToast.push("下载apk成功。尝试安装：" + apkFile.getAbsolutePath());
            installApk(apkFile);
        } catch (Exception e) {
            Util.debugException(e, "下载apk");
            mainActivity.tvToast.push("下载apk失败：" + Util.getExceptionMessage(e));
        }
    }

    private void installApk(File apkFile) {
        try {
            Uri apkUri;
            Intent intent = new Intent(Intent.ACTION_VIEW);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // For Android N and above, use FileProvider to grant temporary read permission to the APK.
                apkUri = FileProvider.getUriForFile(mainActivity, mainActivity.getPackageName() + ".provider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // Required for starting an Activity from a non-Activity context.

            mainActivity.startActivity(intent);
        } catch (Exception e) {
            Util.debugException(e, "安装" + apkFile.getAbsolutePath());
            mainActivity.tvToast.push("安装" + apkFile.getAbsolutePath() + "失败：" + Util.getExceptionMessage(e));
        }
    }

}

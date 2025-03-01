package qidizi.tv;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayDeque;

public class TvToast {
    private int index = 0;
    private final TextView toastView;
    private final int maxQueue = 30;
    // x个消息足够了
    private final ArrayDeque<String> msgQueue = new ArrayDeque<>(maxQueue);
    private final Handler secHandler = new Handler(Looper.getMainLooper());
    private boolean isNew = false;
    private final Runnable secRunnable = new Runnable() {
        @Override
        public void run() {
            secHandler.postDelayed(this, 1000);// 固定间隔执行，因为view.setText太快会闪退

            synchronized (msgQueue) {
                if (!isNew) return;
                // 内容只有从无到有，不会从有到无
                StringBuilder text = new StringBuilder();
                for (String str : msgQueue) {
                    text.append(str);
                    text.append("\n");
                }
                // 不能检测可见才设置，否则其它逻辑变为可见逻辑就不对了
                toastView.setText(text.toString());
            }
        }
    };

    protected TvToast(MainActivity activity) {
        toastView = activity.findViewById(R.id.toastView);
        create();
    }

    private void create() {
        // todo 不同设备分辨率文本差距非常大，可能需要找到一个动态适配的方案
        toastView.setTextSize(StaticData.baseTextSizeSp);
        // 立刻执行
        secRunnable.run();
    }

    protected void push(final String str) {
        // 为了简化逻辑减轻性能消耗，这里只插入，不主动显示，等定时启动时显示；
        // todo 有可能启动时，视频变播放状态，完全看不到；看看实际使用再考虑实时显示
        // 用线程，避免阻塞push线程
        new Thread(() -> {
            synchronized (msgQueue) {
                isNew = true;
                if (msgQueue.size() >= maxQueue) {
                    msgQueue.pollLast();
                }
                msgQueue.push(++index + ". " + str);
            }
        }).start();
    }

    protected void show() {
        toastView.setVisibility(View.VISIBLE);
    }

    protected void hide() {
        toastView.setVisibility(View.GONE);
    }

    protected void destroy() {
        secHandler.removeCallbacks(secRunnable);
    }
}

package nj.finley.keepscreenon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private int originalBrightness = -1;

    private final ActivityResultLauncher<Intent> writeSettingsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (Settings.System.canWrite(this)) {
            initDisplaySettings();
        } else {
            Toast.makeText(this, "需要权限才能运行", Toast.LENGTH_SHORT).show();
            finish();
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 隐藏ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // 1. 全屏黑色背景（无布局文件，直接创建View）
        View rootView = new View(this);
        rootView.setBackgroundColor(0xFF000000); // 纯黑背景
        rootView.setKeepScreenOn(true); // 屏幕常亮
        setContentView(rootView);

        // 2. API 30+ 沉浸式全屏（隐藏状态栏和导航栏）
        setImmersiveFullScreen();

        // 3. 检查并申请系统设置修改权限
        if (!Settings.System.canWrite(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(android.net.Uri.parse("package:" + getPackageName()));
            writeSettingsLauncher.launch(intent);
        } else {
            initDisplaySettings();
        }
    }

    /**
     * API 30+ 沉浸式全屏实现（替代SYSTEM_UI_FLAG_*）
     */
    private void setImmersiveFullScreen() {
        Window window = getWindow();
        // 隐藏状态栏和导航栏
        WindowInsetsController insetsController = window.getInsetsController();
        if (insetsController != null) {
            insetsController.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
            // 设置交互时不显示状态栏和导航栏（完全沉浸式）
            insetsController.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        // 确保内容延伸至全屏区域
        window.setDecorFitsSystemWindows(false);
    }

    private void initDisplaySettings() {
        // 获取原始亮度（未找到时返回默认值）
        originalBrightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 100 // 默认值
        );

        // 4. 降低屏幕亮度（0-255，最低值1）
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 1);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        getWindow().setAttributes(params);

        // 5. 降低分辨率和帧率（API 30+ 兼容）
        setLowResolutionAndFrameRate();

        // 6. 降低系统性能占用
        reduceSystemPerformance();
    }

    /**
     * 降低分辨率和帧率
     */
    private void setLowResolutionAndFrameRate() {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);

        Display.Mode[] supportedModes = defaultDisplay.getSupportedModes();
        if (supportedModes == null || supportedModes.length == 0) {
            Toast.makeText(this, "不支持分辨率/帧率调整", Toast.LENGTH_SHORT).show();
            return;
        }

        // --- 核心省电逻辑开始 ---

        // 1. 找出所有模式中的最低刷新率
        float minRefreshRate = Float.MAX_VALUE;
        for (Display.Mode mode : supportedModes) {
            if (mode.getRefreshRate() < minRefreshRate) {
                minRefreshRate = mode.getRefreshRate();
            }
        }

        // 2. 筛选出所有拥有最低刷新率的模式
        List<Display.Mode> candidatesWithMinFrameRate = new ArrayList<>();
        for (Display.Mode mode : supportedModes) {
            if (Math.abs(mode.getRefreshRate() - minRefreshRate) < 0.01f) { // 用一个小epsilon避免浮点精度问题
                candidatesWithMinFrameRate.add(mode);
            }
        }

        // 3. 在这些候选模式中，选择分辨率最低的（即像素面积最小的）
        Display.Mode bestPowerSavingMode = candidatesWithMinFrameRate.get(0);
        long minArea = (long) bestPowerSavingMode.getPhysicalWidth() * bestPowerSavingMode.getPhysicalHeight();
        for (Display.Mode mode : candidatesWithMinFrameRate) {
            long area = (long) mode.getPhysicalWidth() * mode.getPhysicalHeight();
            if (area < minArea) {
                minArea = area;
                bestPowerSavingMode = mode;
            }
        }

        // --- 核心省电逻辑结束 ---

        // 4. 应用找到的最优省电模式
        try {
            WindowManager.LayoutParams windowParams = getWindow().getAttributes();
            windowParams.preferredDisplayModeId = bestPowerSavingMode.getModeId();
            getWindow().setAttributes(windowParams);

            @SuppressLint("DefaultLocale") String message = String.format("已切换至省电模式：%dx%d @ %.1fHz",
                    bestPowerSavingMode.getPhysicalWidth(),
                    bestPowerSavingMode.getPhysicalHeight(),
                    bestPowerSavingMode.getRefreshRate());
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "切换省电模式失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 降低系统性能占用（API 30+ 安全方式）
     */
    private void reduceSystemPerformance() {
        // 1. 关闭硬件加速（减少GPU占用）
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, 0);

        // 2. 降低应用线程优先级
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);

        // 3. 建议用户开启系统省电模式（非强制）
        Intent powerSaverIntent = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS);
        // 如需强制跳转可取消注释，但可能影响用户体验
        // startActivity(powerSaverIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 恢复亮度设置
        if (originalBrightness != -1) {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, originalBrightness);
        }
        // 恢复窗口亮度为系统默认
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);
    }
}
package com.wms.pda.ui.common;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.wms.pda.R;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.scan.ScanResult;
import com.wms.pda.scan.ScanType;
import com.wms.pda.scan.ScannerCallback;

/**
 * 条码扫码面板:摄像头取景 + 手动输入编码。
 *
 * 为什么需要它:条码扫码原先只有 CHAINWAY **硬件扫把头**一条路(见 ScanManager),
 * 没有扫码头的手持设备(普通安卓手机)点「条码扫码」只会静默无反应 ——
 * ScanManager 里那份 CameraX + ML Kit 的兜底实现,从没被任何界面接上过。
 *
 * 为什么用 DialogFragment(而其它弹窗是 AlertDialog 助手 + 静态 show):
 *  1) 摄像头要一个 LifecycleOwner 才能 bindToLifecycle —— 弹窗自己就是,
 *     关掉即解绑,不用宿主记着释放;
 *  2) 相机是运行时权限,launcher 必须在 STARTED 之前注册,这只有 Fragment/Activity 能做。
 * 两条 AlertDialog 助手都给不了,所以这里另起一种;结果用 Fragment Result 回传宿主
 * (见 {@link #REQUEST_KEY}),这样旋转重建后回调也不会丢。
 *
 * 超时只**提示**不停止:面板开着就是在等人对准,自动停会变成"还没对准就被打断"。
 * 硬件扫把头那两条路是持续扫的,更不能加超时(见 ScannerCallback 的说明)。
 */
public class BarcodeScanDialog extends DialogFragment {

    public static final String TAG = "barcode_scan";
    /** 结果回传的键:宿主用 setFragmentResultListener(REQUEST_KEY, ...) 接 */
    public static final String REQUEST_KEY = "barcode_scan_result";
    public static final String KEY_CODE = "code";

    /** 多久还没识别到,就把提示换成"对准一点"(只提示,不停扫描) */
    private static final long SLOW_HINT_MS = 20_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ActivityResultLauncher<String> permissionLauncher;
    private PreviewView previewView;
    private TextView tvHint;
    private EditText etManual;
    private Context appCtx;
    private boolean cameraStarted;
    private boolean permissionAsked;

    /** 单入口:面板已经在显示就不重复弹(连点两次不会叠两层)。 */
    public static void show(@NonNull FragmentManager fm) {
        if (fm.findFragmentByTag(TAG) != null) return;
        new BarcodeScanDialog().show(fm, TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appCtx = requireContext().getApplicationContext();
        // 必须在 STARTED 之前注册,否则抛 IllegalStateException
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) startCamera(); else onCameraDenied(); });
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View v = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_barcode_scan, null, false);
        previewView = v.findViewById(R.id.pv_scan);
        tvHint = v.findViewById(R.id.tv_scan_hint);
        etManual = v.findViewById(R.id.et_manual_code);

        AlertDialog d = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.scan_barcode)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        // 「确认」= 提交手动输入的码。输入框空着就别关窗 —— 否则用户会以为输进去了。
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(b -> { if (submitManual()) d.dismiss(); }));
        return d;
    }

    @Override
    public void onStart() {
        super.onStart();
        handler.postDelayed(this::showSlowHint, SLOW_HINT_MS);
        if (hasCameraPermission()) {
            startCamera();
        } else if (!permissionAsked) {
            permissionAsked = true;
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (appCtx != null) {
            ScanManager.get(appCtx).stopScan();
            ScanManager.get(appCtx).detachBarcodeCamera();
        }
        super.onDestroy();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        if (cameraStarted || previewView == null) return;
        cameraStarted = true;
        ScanManager.get(appCtx).startScan(ScanType.BARCODE, new ScannerCallback() {
            @Override
            public void onScanResult(ScanResult result) {
                deliver(result == null ? null : result.getCode());
            }

            @Override
            public void onError(int code, String msg) {
                if (tvHint != null) tvHint.setText(getString(R.string.msg_scan_exception, msg));
            }
        });
        // 绑弹窗自己(它没走 onCreateView,getViewLifecycleOwner() 在这条路上是空的)
        ScanManager.get(appCtx).attachBarcodeCamera(this, previewView);
    }

    /** 权限被拒(或被"不再询问"):退成手动输入,并说清为什么没有画面。 */
    private void onCameraDenied() {
        if (tvHint != null) tvHint.setText(R.string.scan_barcode_no_permission);
        if (etManual != null) etManual.requestFocus();
    }

    private void showSlowHint() {
        if (tvHint != null && cameraStarted) tvHint.setText(R.string.scan_barcode_hint_slow);
    }

    /** @return 有码可交(交出去了)就 true */
    private boolean submitManual() {
        String code = etManual == null ? "" : etManual.getText().toString().trim();
        if (code.isEmpty()) return false;
        deliver(code);
        return true;
    }

    private void deliver(@Nullable String code) {
        if (code == null || code.isEmpty()) return;
        Bundle b = new Bundle();
        b.putString(KEY_CODE, code);
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, b);
        dismiss();
    }
}

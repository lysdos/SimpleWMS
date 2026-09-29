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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import androidx.fragment.app.Fragment;
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
 *
 * **连续扫**:面板不关,每读到一个码就交给宿主一个(同一个码 2 秒内不重复报,见
 * BarcodeScannerImpl),关窗由用户点「完成」—— 与扫码头"一直扫到点停止"的手感一致。
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
    /** 是否已经交付过至少一个码 —— 交付过就别再拿"对准一点"覆盖掉"最近读到什么" */
    private boolean anyDelivered;

    /** 扫描回调:每次 onStart 重新武装扫描状态时复用同一个实例 */
    private final ScannerCallback scanCallback = new ScannerCallback() {
        @Override
        public void onScanResult(ScanResult result) {
            deliver(result == null ? null : result.getCode());
        }

        @Override
        public void onError(int code, String msg) {
            if (tvHint != null) tvHint.setText(getString(R.string.msg_scan_exception, msg));
        }
    };

    /**
     * 单入口:面板**正在显示**就不重复弹(连点两次不会叠两层)。
     *
     * 判据必须落在"确实还显示着"上:只判 `findFragmentByTag != null` 的话,上一个面板
     * `dismiss()` 的移除事务还没执行完时仍能查到它,于是**下一次点击什么都不做** ——
     * 现场表现就是"扫过一次之后,再点条码扫码不弹面板了"。
     */
    public static void show(@NonNull FragmentManager fm) {
        Fragment existing = fm.findFragmentByTag(TAG);
        if (existing instanceof DialogFragment
                && ((DialogFragment) existing).getDialog() != null
                && ((DialogFragment) existing).getDialog().isShowing()) {
            return;
        }
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
        // 手输时把取景收起来:输入框立刻上到内容顶部,软键盘再高也盖不到它。
        // 为什么非这样不可:弹窗居中、高度固定,而 SOFT_INPUT_ADJUST_RESIZE 在 Android 11+
        // 对 Dialog 常不生效 —— 键盘从下面盖上来,盖住的正好是原来贴底的那一格输入框。
        etManual.setOnFocusChangeListener((fv, hasFocus) -> setManualMode(hasFocus));
        // 键盘上的「完成」= 提交这个码 + 回取景(没有它的话,ADJUST_RESIZE 失效的机型上
        // 按返回键不会让输入框失焦,取景就一直藏着,用户找不到回去的路)
        etManual.setOnEditorActionListener((fv, actionId, e) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            submitManual();
            return true;
        });

        AlertDialog d = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.scan_barcode)
                .setView(v)
                .setPositiveButton(R.string.action_confirm, null)
                .setNegativeButton(R.string.action_done, null)
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        // 「确认」= 提交手动输入的码(提交后面板**不关**,可以接着输下一个;空输入不做任何事)
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(b -> submitManual()));
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

    /**
     * 开始/恢复扫描。
     *
     * 相机只绑一次,但**扫描状态每次 onStart 都要重新武装**:宿主 Activity 在 `onPause()` 里会
     * `stopScan()`(那是给页面上原地扫码用的),而面板现在不再自动关 —— 息屏/切走再回来时,
     * 面板看着还在、`scanning` 却已经是 false,表现就是"面板开着却怎么都不出码"。
     * 重新 startScan 只是把标记置回 true(并重置重复抑制),不会重绑相机。
     */
    private void startCamera() {
        if (previewView == null) return;
        ScanManager.get(appCtx).startScan(ScanType.BARCODE, scanCallback);
        if (cameraStarted) return;
        cameraStarted = true;
        // 绑弹窗自己(它没走 onCreateView,getViewLifecycleOwner() 在这条路上是空的)
        ScanManager.get(appCtx).attachBarcodeCamera(this, previewView);
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

    /** 权限被拒(或被"不再询问"):退成手动输入,并说清为什么没有画面。 */
    private void onCameraDenied() {
        if (tvHint != null) tvHint.setText(R.string.scan_barcode_no_permission);
        if (etManual != null) etManual.requestFocus();
    }

    private void showSlowHint() {
        if (tvHint != null && cameraStarted && !anyDelivered) {
            tvHint.setText(R.string.scan_barcode_hint_slow);
        }
    }

    /**
     * 输入态:收起取景(让输入框上到顶部),并**暂停扫描** —— 收起预览不等于停止识别,
     * 镜头仍在分析,手输时货架上任何一个标签都可能被自动报进来(在出库页那就是误确认一件货)。
     * 退出输入态时重新武装扫描(相机只绑一次,这里只是把标记置回 true)。
     */
    private void setManualMode(boolean on) {
        if (previewView != null) previewView.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) {
            ScanManager.get(appCtx).stopScan();
        } else if (cameraStarted) {
            ScanManager.get(appCtx).startScan(ScanType.BARCODE, scanCallback);
        }
    }

    /** 提交一个手输的码:交出去 → 收键盘 → 丢焦点(取景随之回来、扫描重新武装)。 */
    private void submitManual() {
        if (etManual == null) return;
        String code = etManual.getText().toString().trim();
        if (code.isEmpty()) return;
        deliver(code);
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etManual.getWindowToken(), 0);
        etManual.clearFocus();   // 触发上面的焦点监听 → 取景回来
    }

    /**
     * 把一个码交给宿主。**不关窗** —— 面板是连续扫的,关窗由用户点「完成」。
     * 成败由宿主那边的提示照常说;这里只把"最近读到什么"留在面板上,免得用户不知道有没有读到。
     */
    private void deliver(@Nullable String code) {
        if (code == null || code.isEmpty()) return;
        anyDelivered = true;
        Bundle b = new Bundle();
        b.putString(KEY_CODE, code);
        getParentFragmentManager().setFragmentResult(REQUEST_KEY, b);
        if (tvHint != null) tvHint.setText(getString(R.string.scan_barcode_last_fmt, code));
        if (etManual != null) etManual.setText("");   // 手动输入:清空,方便接着输下一个
    }
}

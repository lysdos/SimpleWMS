package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 光学条码实现 - Android CameraX + ML Kit barcode scanning(实时识别)。
 *
 * 支持 24 位字母数字的 CODE_128 等常见码制,直接通过 PDA 后置摄像头扫描。
 * RFID 与 Barcode 互相独立,通过 ScanType 区分调用。
 *
 * 用法(由 Activity 配合):
 * 1. layout 放一个 PreviewView(previewView)
 * 2. attachCamera(lifecycleOwner, previewView) 触发摄像头预览 + 识别
 * 3. setCallback(cb) 设置结果回调
 * 4. 用户也可在 detachCamera() 释放资源
 */
public class BarcodeScannerImpl implements ScannerService {

    private static final String TAG = "BarcodeScannerImpl";
    /** 懒创建,见 mlKit() */
    private volatile BarcodeScanner mlKitScanner;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile ScannerCallback cb;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;

    /**
     * 懒创建 ML Kit 客户端。
     *
     * **不能放在构造函数里**:ScanManager 会无条件 new 出一个摄像头兜底实例,而
     * `BarcodeScanning.getClient` 会触发 ML Kit 动态模块加载 —— 真机 logcat 里那条
     * `DynamiteModule` 就是它(十几毫秒,量不大,但发生在冷启动的主线程上)。
     * 有硬件扫把头的 PDA 永远走不到这里,所以只有真要用摄像头时才建,且调用点在
     * analysisExecutor 上,不占主线程。
     *
     * 注:冷启动那近 2 秒的卡顿不是这里,是 ScanManager 在主线程同步调厂商的
     * `BarcodeFactory...open()`(见 BarcodeHardwareScannerImpl.init)。
     */
    private BarcodeScanner mlKit() {
        BarcodeScanner s = mlKitScanner;
        if (s == null) {
            synchronized (this) {
                if (mlKitScanner == null) {
                    BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(
                                    Barcode.FORMAT_CODE_128,    // 24 位字母数字
                                    Barcode.FORMAT_CODE_39,
                                    Barcode.FORMAT_CODE_93,
                                    Barcode.FORMAT_EAN_13,
                                    Barcode.FORMAT_EAN_8,
                                    Barcode.FORMAT_QR_CODE,
                                    Barcode.FORMAT_UPC_A,
                                    Barcode.FORMAT_UPC_E)
                            .build();
                    mlKitScanner = BarcodeScanning.getClient(options);
                }
                s = mlKitScanner;
            }
        }
        return s;
    }

    @Override
    public void init(Context ctx) {
        // ML Kit barcode 不需要显式 init(BarcodeScanning.getClient 已 lazy init)
        // CameraX 需要在调用 attachCamera 时绑定 LifecycleOwner
        // 这里只标记就绪状态;具体摄像头绑定由 Activity 调 attachCamera
        // 兜底:如果 PDA 没摄像头,scanType 调用时 isReady=false
    }

    @Override
    public void release() {
        scanning.set(false);
        try {
            if (mlKitScanner != null) mlKitScanner.close();
        } catch (Throwable ignored) {}
        analysisExecutor.shutdown();
        detachCamera();
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.BARCODE) return;
        scanning.set(true);
        // 摄像头预览 + 识别由 attachCamera 启动,这里仅标记状态
        // 实际回调在 analyze() 中触发 cb.onScanResult
    }

    @Override
    public void stopScan() {
        scanning.set(false);
    }

    /** 摄像头路径可用 = ML Kit 客户端已建(即 attachCamera 之后);没绑摄像头就谈不上可用 */
    @Override
    public boolean isReady() {
        return mlKitScanner != null;
    }

    @Override
    public void setCallback(ScannerCallback cb) {
        this.cb = cb;
    }

    /**
     * 由 Activity 在 onCreate/onResume 调:绑定 CameraX 预览 + 图像分析到 LifecycleOwner。
     * previewView 用于显示摄像头画面,建议全屏 + 居中。
     */
    public void attachCamera(@NonNull LifecycleOwner owner, @NonNull PreviewView previewView) {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(previewView.getContext());
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyze);

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(owner, selector, preview, analysis);
            } catch (Throwable t) {
                Log.e(TAG, "attachCamera failed", t);
                ScannerCallback localCb = cb;
                if (localCb != null) localCb.onError(-1, "摄像头不可用: " + t.getMessage());
            }
        }, ContextCompat.getMainExecutor(previewView.getContext()));
    }

    /** Activity onPause 时调,释放摄像头。 */
    public void detachCamera() {
        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                camera = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "detachCamera failed", t);
        }
    }

    private void analyze(@NonNull ImageProxy imageProxy) {
        if (!scanning.get()) {
            imageProxy.close();
            return;
        }
        @androidx.camera.core.ExperimentalGetImage
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }
        InputImage inputImage = InputImage.fromMediaImage(
                mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        mlKit().process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    if (barcodes != null && !barcodes.isEmpty() && scanning.get()) {
                        for (Barcode b : barcodes) {
                            String raw = b.getRawValue();
                            if (raw != null && !raw.isEmpty()) {
                                // 抓到一个就停 + 回调
                                scanning.set(false);
                                ScannerCallback localCb = cb;
                                if (localCb != null) {
                                    localCb.onScanResult(new ScanResult(
                                            raw, ScanType.BARCODE, null,
                                            System.currentTimeMillis()));
                                }
                                break;
                            }
                        }
                    }
                })
                .addOnFailureListener(t -> Log.d(TAG, "scan failed", t))
                .addOnCompleteListener(task -> imageProxy.close());
    }
}

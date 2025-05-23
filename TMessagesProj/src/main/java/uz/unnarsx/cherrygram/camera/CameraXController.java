/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2025.
 */

package uz.unnarsx.cherrygram.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Range;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.core.impl.utils.Exif;
import androidx.camera.core.internal.compat.workaround.ExifRotationAvailability;
import androidx.camera.extensions.ExtensionMode;
import androidx.camera.extensions.ExtensionsManager;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.camera.Size;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import uz.unnarsx.cherrygram.core.configs.CherrygramCameraConfig;

public class CameraXController {

    private boolean isFrontface;
    private boolean isInitiated = false;
    private final CameraLifecycle lifecycle;
    private ProcessCameraProvider provider;
    private static Camera camera;
    private CameraSelector cameraSelector;
    private CameraXView.VideoSavedCallback videoSavedCallback;
    private boolean abandonCurrentVideo = false;
    private ImageCapture iCapture;
    private Preview previewUseCase;
    private VideoCapture<Recorder> vCapture;
    private Recording recording;
    private final MeteringPointFactory meteringPointFactory;
    private final Preview.SurfaceProvider surfaceProvider;
    private ExtensionsManager extensionsManager;
    private boolean stableFPSPreviewOnly = false;
    private boolean noSupportedSurfaceCombinationWorkaround = false;
    public static final int CAMERA_NONE = 0;
    public static final int CAMERA_NIGHT = 1;
    public static final int CAMERA_HDR = 2;
    public static final int CAMERA_AUTO = 3;
    public static final int CAMERA_WIDE = 4;
    public float oldZoomSelection = CherrygramCameraConfig.INSTANCE.getStartFromUltraWideCam() ? 0F : 5F;
    private int selectedEffect = CAMERA_NONE;

    public static class CameraLifecycle implements LifecycleOwner {

        private final LifecycleRegistry lifecycleRegistry;

        public CameraLifecycle() {
            lifecycleRegistry = new LifecycleRegistry(this);
            lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        }

        public void start() {
            try {
                lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
            } catch (IllegalStateException ignored) {
            }
        }

        public void stop() {
            try {
                lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
            } catch (IllegalStateException ignored) {
            }
        }

        @NonNull
        public Lifecycle getLifecycle() {
            return lifecycleRegistry;
        }

    }

    public CameraXController(CameraLifecycle lifecycle, MeteringPointFactory factory, Preview.SurfaceProvider surfaceProvider) {
        this.lifecycle = lifecycle;
        this.meteringPointFactory = factory;
        this.surfaceProvider = surfaceProvider;
    }

    public boolean isInitied() {
        return isInitiated;
    }

    public boolean setFrontFace(boolean isFrontFace) {
        return this.isFrontface = isFrontFace;
    }

    public boolean isFrontface() {
        return isFrontface;
    }

    public void setStableFPSPreviewOnly(boolean isEnabled) {
        stableFPSPreviewOnly = isEnabled;
    }

    public void initCamera(Context context, boolean isInitialFrontface, Runnable onPreInit) {
        this.isFrontface = isInitialFrontface;
        ListenableFuture<ProcessCameraProvider> providerFtr = ProcessCameraProvider.getInstance(context);
        providerFtr.addListener(
                () -> {
                    try {
                        provider = providerFtr.get();
                        ListenableFuture<ExtensionsManager> extensionFuture = ExtensionsManager.getInstanceAsync(context, provider);
                        extensionFuture.addListener(() -> {
                            try {
                                extensionsManager = extensionFuture.get();
                                bindUseCases();
                                lifecycle.start();
                                onPreInit.run();
                                isInitiated = true;
                            } catch (ExecutionException | InterruptedException e) {
                                e.printStackTrace();
                            }
                        }, ContextCompat.getMainExecutor(context));
                    } catch (ExecutionException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }, ContextCompat.getMainExecutor(context)
        );
    }

    public void setCameraEffect(@EffectFacing int effect) {
        selectedEffect = effect;
        bindUseCases();
    }

    public int getCameraEffect() {
        return selectedEffect;
    }

    public void switchCamera() {
        isFrontface = !isFrontface;
        bindUseCases();
    }

    public void closeCamera() {
        lifecycle.stop();
    }

    @SuppressLint("RestrictedApi")
    public boolean hasFrontFaceCamera() {
        if (provider == null) {
            return false;
        }
        try {
            return provider.hasCamera(
                    new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build());
        } catch (CameraInfoUnavailableException e) {
            return false;
        }
    }

    @SuppressLint("RestrictedApi")
    public static boolean hasGoodCamera(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

//    public int setNextFlashMode() {
//        int next = switch (iCapture.getFlashMode()) {
//            case ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON;
//            case ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF;
//            default -> ImageCapture.FLASH_MODE_AUTO;
//        };
//
//        iCapture.setFlashMode(next);
//        return next;
//    }

    public int setNextFlashMode() {
        int currentMode = iCapture.getFlashMode();
        int nextMode = (currentMode == ImageCapture.FLASH_MODE_AUTO) ? ImageCapture.FLASH_MODE_ON
                : (currentMode == ImageCapture.FLASH_MODE_ON) ? ImageCapture.FLASH_MODE_OFF
                : ImageCapture.FLASH_MODE_AUTO;

        iCapture.setFlashMode(nextMode);
        return nextMode;
    }

    public int getCurrentFlashMode() {
        return iCapture.getFlashMode();
    }

    public static boolean isFlashAvailable() {
        return camera.getCameraInfo().hasFlashUnit();
    }

    public static void setTorchEnabled(boolean enabled) {
        if (isFlashAvailable()) {
            camera.getCameraControl().enableTorch(enabled);
        }
    }

    public boolean isAvailableHdrMode() {
        if (extensionsManager != null) {
            try {
                return extensionsManager.isExtensionAvailable(cameraSelector, ExtensionMode.HDR);
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean isAvailableNightMode() {
        if (extensionsManager != null) {
            try {
                return extensionsManager.isExtensionAvailable(cameraSelector, ExtensionMode.NIGHT);
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean isAvailableWideMode() {
        if (provider != null) {
            return CameraXUtils.isWideAngleAvailable(provider);
        } else {
            return false;
        }
    }

    public boolean isAvailableAutoMode() {
        if (extensionsManager != null) {
            try {
                return extensionsManager.isExtensionAvailable(cameraSelector, ExtensionMode.AUTO);
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        } else {
            return false;
        }
    }

    public android.util.Size getVideoBestSize() {
        int w, h;
        android.util.Size size = CameraXUtils.getPreviewBestSize();
        w = size.getWidth();
        h = size.getHeight();
        if ((getDisplayOrientation() == 0 || getDisplayOrientation() == 180) && getDeviceDefaultOrientation() == Configuration.ORIENTATION_PORTRAIT) {
            return new android.util.Size(h, w);
        } else {
            return new android.util.Size(w, h);
        }
    }

    @SuppressLint({"RestrictedApi", "UnsafeExperimentalUsageError", "UnsafeOptInUsageError"})
    public void bindUseCases() {
        if (provider == null) return;

        Preview.Builder previewBuilder = new Preview.Builder();
        previewBuilder.setTargetResolution(getVideoBestSize());

        if (CherrygramCameraConfig.INSTANCE.getCameraXFpsRange() != CherrygramCameraConfig.CameraXFpsRangeDefault) {
            previewBuilder.setTargetFrameRate(VideoMessagesHelper.getCameraXFpsRange());
        }

        if (!isFrontface && selectedEffect == CAMERA_WIDE) {
            cameraSelector = CameraXUtils.getDefaultWideAngleCamera(provider);
        } else {
            cameraSelector = isFrontface ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        }

        int extensionMode = (isFrontface) ? ExtensionMode.NONE : switch (selectedEffect) {
            case CAMERA_NIGHT -> ExtensionMode.NIGHT;
            case CAMERA_HDR -> ExtensionMode.HDR;
            case CAMERA_AUTO -> ExtensionMode.AUTO;
            default -> ExtensionMode.NONE;
        };
        cameraSelector = extensionsManager.getExtensionEnabledCameraSelector(cameraSelector, extensionMode);

        Quality quality = CameraXUtils.getVideoQuality();
        QualitySelector selector = QualitySelector.from(quality, FallbackStrategy.higherQualityOrLowerThan(quality));
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(selector)
                .setAspectRatio(VideoMessagesHelper.getCameraXAspectRatio())
                .build();

        vCapture = VideoCapture.withOutput(recorder);

        ImageCapture.Builder iCaptureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(VideoMessagesHelper.getCameraXAspectRatio());

        provider.unbindAll();
        previewUseCase = previewBuilder.build();
        previewUseCase.setSurfaceProvider(surfaceProvider);

        if (lifecycle.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) return;
        if (stableFPSPreviewOnly) {
            camera = provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase, vCapture);

            if (CherrygramCameraConfig.INSTANCE.getCameraStabilisation()
                    || CherrygramCameraConfig.INSTANCE.getCameraXFpsRange() != CherrygramCameraConfig.CameraXFpsRangeDefault
            ) {
                CaptureRequestOptions.Builder captureRequestOptions = new CaptureRequestOptions.Builder();

                if (CherrygramCameraConfig.INSTANCE.getCameraStabilisation()) {
                    captureRequestOptions.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                    captureRequestOptions.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
                }

                if (CherrygramCameraConfig.INSTANCE.getCameraXFpsRange() != CherrygramCameraConfig.CameraXFpsRangeDefault) {
                    captureRequestOptions.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, VideoMessagesHelper.getCameraXFpsRange());
                }

                Camera2CameraControl cameraControl = Camera2CameraControl.from(camera.getCameraControl());
                cameraControl.setCaptureRequestOptions(captureRequestOptions.build());
            }
        } else {
            iCapture = iCaptureBuilder.build();
            try {
                camera = provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase, vCapture, iCapture);
                noSupportedSurfaceCombinationWorkaround = false;
            } catch (IllegalArgumentException e) {
                noSupportedSurfaceCombinationWorkaround = true;
                try {
                    camera = provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase, iCapture);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        if (camera != null) {
            camera.getCameraControl().setLinearZoom(oldZoomSelection);
        }
    }

    public void setZoom(float value) {
        camera.getCameraControl().setLinearZoom(oldZoomSelection = value);
    }

    public float resetZoom() {
        if (camera == null) return 0.0f;

        camera.getCameraControl().setLinearZoom(0f);
        ZoomState zoomStateLiveData = camera.getCameraInfo().getZoomState().getValue();
        return (zoomStateLiveData != null) ? (oldZoomSelection = zoomStateLiveData.getLinearZoom()) : 0.0f;
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    public boolean isExposureCompensationSupported() {
        if (camera == null) return false;
        return camera.getCameraInfo().getExposureState().isExposureCompensationSupported();
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    public void setExposureCompensation(float value) {
        if (camera == null) return;
        if (!camera.getCameraInfo().getExposureState().isExposureCompensationSupported()) return;
        Range<Integer> evRange = camera.getCameraInfo().getExposureState().getExposureCompensationRange();
        int index = (int) (mix(evRange.getLower().floatValue(), evRange.getUpper().floatValue(), value) + 0.5f);
        camera.getCameraControl().setExposureCompensationIndex(index);
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public void setTargetOrientation(int rotation) {
        if (previewUseCase != null) {
            previewUseCase.setTargetRotation(rotation);
        }
        if (iCapture != null) {
            iCapture.setTargetRotation(rotation);
        }
        if (vCapture != null) {
            vCapture.setTargetRotation(rotation);
        }
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public void setWorldCaptureOrientation(int rotation) {
        if (iCapture != null) {
            iCapture.setTargetRotation(rotation);
        }
        if (vCapture != null) {
            vCapture.setTargetRotation(rotation);
        }
    }

    @SuppressLint({"UnsafeExperimentalUsageError", "RestrictedApi"})
    public void focusToPoint(int x, int y/*, boolean disableAutoCancel*/) {
        MeteringPoint point = meteringPointFactory.createPoint(x, y);
        FocusMeteringAction.Builder actionBuilder = new FocusMeteringAction.Builder(
                point, FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AWB
        );

//        if (disableAutoCancel) actionBuilder.disableAutoCancel();

        camera.getCameraControl().startFocusAndMetering(actionBuilder.build());
    }


    @SuppressLint({"RestrictedApi", "MissingPermission"})
    public void recordVideo(final File path, boolean mirror, CameraXView.VideoSavedCallback onStop) {
        if (noSupportedSurfaceCombinationWorkaround) {
            provider.unbindAll();
            provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase, vCapture);
        }
        videoSavedCallback = onStop;
        FileOutputOptions fileOpt = new FileOutputOptions
                .Builder(path)
                .build();

        if (iCapture.getFlashMode() == ImageCapture.FLASH_MODE_ON) {
            camera.getCameraControl().enableTorch(true);
        }
        recording = vCapture.getOutput()
                .prepareRecording(ApplicationLoader.applicationContext, fileOpt)
                .withAudioEnabled()
                .start(AsyncTask.THREAD_POOL_EXECUTOR, videoRecordEvent -> {
                    if (videoRecordEvent instanceof VideoRecordEvent.Finalize finalize) {
                        if (finalize.hasError()) {
                            if (noSupportedSurfaceCombinationWorkaround) {
                                AndroidUtilities.runOnUIThread(() -> {
                                    provider.unbindAll();
                                    provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase, iCapture);
                                });
                            }
                            FileLog.e(finalize.getCause());
                        } else {
                            if (noSupportedSurfaceCombinationWorkaround) {
                                AndroidUtilities.runOnUIThread(() -> {
                                    provider.unbindAll();
                                    provider.bindToLifecycle(lifecycle, cameraSelector, previewUseCase, iCapture);
                                });
                            }

                            if (abandonCurrentVideo) {
                                abandonCurrentVideo = false;
                            } else {
                                finishRecordingVideo(path, mirror);
                                if (iCapture.getFlashMode() == ImageCapture.FLASH_MODE_ON) {
                                    camera.getCameraControl().enableTorch(false);
                                }
                            }
                        }
                    }
                });
    }

    private void finishRecordingVideo(final File path, boolean mirror) {
        MediaMetadataRetriever mediaMetadataRetriever = null;
        long duration = 0;
        try {
            mediaMetadataRetriever = new MediaMetadataRetriever();
            mediaMetadataRetriever.setDataSource(path.getAbsolutePath());
            String d = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                duration = (int) Math.ceil(Long.parseLong(d) / 1000.0f);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (mediaMetadataRetriever != null) {
                    mediaMetadataRetriever.release();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        Bitmap bitmap = SendMessagesHelper.createVideoThumbnail(path.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
        if (bitmap != null && mirror) {
            Bitmap mirroredBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mirroredBitmap);
            canvas.scale(-1, 1, mirroredBitmap.getWidth() / 2f, mirroredBitmap.getHeight() / 2f);
            canvas.drawBitmap(bitmap, 0, 0, null);
            bitmap.recycle();
            bitmap = mirroredBitmap;
        }

        String fileName = Integer.MIN_VALUE + "_" + SharedConfig.getLastLocalId() + ".jpg";
        final File cacheFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName);
        try {
            FileOutputStream stream = new FileOutputStream(cacheFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 87, stream);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        SharedConfig.saveConfig();
        final long durationFinal = duration;
        final Bitmap bitmapFinal = bitmap;
        AndroidUtilities.runOnUIThread(() -> {
            if (videoSavedCallback != null) {
                String cachePath = cacheFile.getAbsolutePath();
                if (bitmapFinal != null) {
                    ImageLoader.getInstance().putImageToCache(new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmapFinal), Utilities.MD5(cachePath), false);
                }
                videoSavedCallback.onFinishVideoRecording(cachePath, durationFinal);
                videoSavedCallback = null;
            }
        });
    }

    @SuppressLint("RestrictedApi")
    public void stopVideoRecording(final boolean abandon) {
        abandonCurrentVideo = abandon;
        if (recording != null) {
            recording.stop();
        }
    }

    public void takePicture(final File file, Runnable onTake) {
        if (stableFPSPreviewOnly) return;
        iCapture.takePicture(AsyncTask.THREAD_POOL_EXECUTOR, new ImageCapture.OnImageCapturedCallback() {
            @SuppressLint("RestrictedApi")
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                int orientation = image.getImageInfo().getRotationDegrees();
                try {

                    FileOutputStream output = new FileOutputStream(file);

                    int flipState = 0;
                    if (isFrontface && (orientation == 90 || orientation == 270)) {
                        flipState = JpegImageUtils.FLIP_Y;
                    } else if (isFrontface && (orientation == 0 || orientation == 180)) {
                        flipState = JpegImageUtils.FLIP_X;
                    }

                    byte[] jpegByteArray = JpegImageUtils.imageToJpegByteArray(image, flipState);
                    output.write(jpegByteArray);
                    output.close();
                    Exif exif = Exif.createFromFile(file);
                    exif.attachTimestamp();

                    if (new ExifRotationAvailability().shouldUseExifOrientation(image)) {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        buffer.rewind();
                        byte[] data = new byte[buffer.capacity()];
                        buffer.get(data);
                        InputStream inputStream = new ByteArrayInputStream(data);
                        Exif originalExif = Exif.createFromInputStream(inputStream);
                        exif.setOrientation(originalExif.getOrientation());
                    } else {
                        exif.rotate(orientation);
                    }
                    exif.save();
                } catch (JpegImageUtils.CodecFailedException | IOException e) {
                    e.printStackTrace();
                    FileLog.e(e);
                }
                image.close();
                AndroidUtilities.runOnUIThread(onTake);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                FileLog.e(exception);
            }
        });
    }

    @SuppressLint("RestrictedApi")
    public Size getPreviewSize() {
        Size size = new Size(0, 0);
        if (previewUseCase != null) {
            android.util.Size s = previewUseCase.getAttachedSurfaceResolution();
            if (s != null) {
                size = new Size(s.getWidth(), s.getHeight());
            }
        }
        return size;
    }

    public int getDisplayOrientation() {
        WindowManager mgr = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
        int rotation = mgr.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private int getDeviceDefaultOrientation() {
        WindowManager windowManager = (WindowManager) (ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE));
        Configuration config = ApplicationLoader.applicationContext.getResources().getConfiguration();
        int rotation = windowManager.getDefaultDisplay().getRotation();

        if (((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) && config.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
                ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) && config.orientation == Configuration.ORIENTATION_PORTRAIT)) {
            return Configuration.ORIENTATION_LANDSCAPE;
        } else {
            return Configuration.ORIENTATION_PORTRAIT;
        }
    }

    private float mix(Float x, Float y, Float f) {
        return x * (1 - f) + y * f;
    }

    @IntDef({CAMERA_NONE, CAMERA_AUTO, CAMERA_HDR, CAMERA_NIGHT})
    @Retention(RetentionPolicy.SOURCE)
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public @interface EffectFacing {
    }

}

package com.samsung.android.sdk.camera.sample.cases;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.samsung.android.sdk.SsdkUnsupportedException;
import com.samsung.android.sdk.camera.SCamera;
import com.samsung.android.sdk.camera.SCameraCaptureSession;
import com.samsung.android.sdk.camera.SCameraCharacteristics;
import com.samsung.android.sdk.camera.SCameraDevice;
import com.samsung.android.sdk.camera.SCameraManager;
import com.samsung.android.sdk.camera.SCaptureFailure;
import com.samsung.android.sdk.camera.SCaptureRequest;
import com.samsung.android.sdk.camera.SCaptureResult;
import com.samsung.android.sdk.camera.STotalCaptureResult;
import com.samsung.android.sdk.camera.processor.SCameraGifProcessor;
import com.samsung.android.sdk.camera.processor.SCameraProcessor;
import com.samsung.android.sdk.camera.processor.SCameraProcessorManager;
import com.samsung.android.sdk.camera.processor.SCameraProcessorParameter;
import com.samsung.android.sdk.camera.sample.R;
import com.samsung.android.sdk.camera.sample.cases.util.AutoFitTextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Sample_Gif extends Activity {
    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Sample_Gif";
    /**
     * Maximum preview width app will use.
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;
    /**
     * Maximum preview height app will use.
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    /**
     * number of frames to be added into GIF output
     */
    private static final int NUM_OF_FRAME = 20;
    private static final int GIF_WIDTH = 640;
    private static final int GIF_HEIGHT = 480;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * output file path
     */
    String mOutputFileName;

    int numberOfImageCaptured = 0;
    int numberOfImageProcessed = 0;
    private SCamera mSCamera;
    private SCameraManager mSCameraManager;
    /**
     * A reference to the opened {@link com.samsung.android.sdk.camera.SCameraDevice}.
     */
    private SCameraDevice mSCameraDevice;
    private SCameraCaptureSession mSCameraSession;
    /**
     * {@link com.samsung.android.sdk.camera.SCaptureRequest.Builder} for the camera preview and recording
     */
    private SCaptureRequest.Builder mPreviewBuilder;
    private SCaptureRequest.Builder mCaptureBuilder;
    /**
     * ID of the current {@link com.samsung.android.sdk.camera.SCameraDevice}.
     */
    private String mCameraId;
    private SCameraCharacteristics mCharacteristics;
    private SCameraGifProcessor mProcessor;

    /**
     * Input image list
     */
    private List<Bitmap> mInputBitmapList = new ArrayList<>();
    private int mJpegOrientation;
    /**
     * An {@link com.samsung.android.sdk.camera.sample.cases.util.AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;
    private ImageReader mImageReader;
    private TextView mNumImagesTextView;
    private Size mPreviewSize;
    /**
     * A camera related listener/callback will be posted in this handler.
     */
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundHandlerThread;
    /**
     * An orientation listener for jpeg orientation
     */
    private OrientationEventListener mOrientationListener;
    private int mLastOrientation = 0;
    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private boolean isAFTriggered;
    /**
     * True if {@link SCaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER} is triggered.
     */
    private boolean isAETriggered;
    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    /**
     * Current app state.
     */
    private CAMERA_STATE mState = CAMERA_STATE.IDLE;
    /**
     * Callback to receive result from haze removal processor.
     */
    private SCameraGifProcessor.EventCallback mProcessorCallback = new SCameraGifProcessor.EventCallback() {
        @Override
        public void onError(int error) {
            if (getState() == CAMERA_STATE.CLOSING)
                return;

            StringBuilder builder = new StringBuilder();
            builder.append("Fail to create result: ");

            switch (error) {
                case SCameraGifProcessor.NATIVE_PROCESSOR_MSG_DECODING_FAIL: {
                    builder.append("decoding fail");
                    break;
                }

                case SCameraGifProcessor.NATIVE_PROCESSOR_MSG_ENCODING_FAIL: {
                    builder.append("encoding fail");
                    break;
                }

                case SCameraGifProcessor.NATIVE_PROCESSOR_MSG_PROCESSING_FAIL: {
                    builder.append("processing fail");
                    break;
                }

                case SCameraGifProcessor.NATIVE_PROCESSOR_MSG_UNKNOWN_ERROR: {
                    builder.append("unknown error");
                    break;
                }
            }
            showAlertDialog(builder.toString(), false);
            unlockAF();
            clearInputBitmaps();
        }

        @Override
        public void onProcessCompleted(byte[] data) {
            try {
                FileOutputStream output = new FileOutputStream(mOutputFileName);
                output.write(data);
                output.close();
            } catch (IOException e) {
                showAlertDialog("Fail to save output.", false);
            }

            MediaScannerConnection.scanFile(Sample_Gif.this,
                    new String[]{mOutputFileName}, null,
                    new MediaScannerConnection.OnScanCompletedListener() {
                        public void onScanCompleted(String path, Uri uri) {
                            Log.i(TAG, "ExternalStorage Scanned " + path + "-> uri=" + uri);
                        }
                    });

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(Sample_Gif.this, "Saved: " + mOutputFileName, Toast.LENGTH_SHORT).show();
                    mNumImagesTextView.setText("");
                }
            });
            unlockAF();
            clearInputBitmaps();
        }
    };
    // This listener is called when a image is ready in ImageReader
    ImageReader.OnImageAvailableListener mImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {

                @Override
                public void onImageAvailable(ImageReader reader) {
                    try(Image image = reader.acquireNextImage()) {
                        mInputBitmapList.add(decodeToBitmap(image, mJpegOrientation));

                        if (getState() == CAMERA_STATE.CLOSING)
                            return;

                        if (NUM_OF_FRAME == mInputBitmapList.size()) {
                            mProcessor.requestMultiProcess(mInputBitmapList);
                        }
                    }
                }
            };
    private Surface mPreviewSurface;
    /**
     * A {@link com.samsung.android.sdk.camera.SCameraCaptureSession.CaptureCallback} for {@link com.samsung.android.sdk.camera.SCameraCaptureSession#setRepeatingRequest(com.samsung.android.sdk.camera.SCaptureRequest, com.samsung.android.sdk.camera.SCameraCaptureSession.CaptureCallback, android.os.Handler)}
     */
    private SCameraCaptureSession.CaptureCallback mSessionCaptureCallback = new SCameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(SCameraCaptureSession session, SCaptureRequest request, STotalCaptureResult result) {
            // Depends on the current state and capture result, app will take next action.
            switch (getState()) {

                case IDLE:
                case PREVIEW:
                case TAKE_PICTURE:
                case CLOSING:
                    // do nothing
                    break;

                // If AF is triggered and AF_STATE indicates AF process is finished, app will trigger AE pre-capture.
                case WAIT_AF: {
                    if (isAFTriggered) {
                        int afState = result.get(SCaptureResult.CONTROL_AF_STATE);
                        // Check if AF is finished.
                        if (SCaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                                SCaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {

                            // If device is legacy device then skip AE pre-capture.
                            if (mCharacteristics.get(SCameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) != SCameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                                triggerAE();
                            } else {
                                captureBurst();
                            }
                            isAFTriggered = false;
                        }
                    }
                    break;
                }

                // If AE is triggered and AE_STATE indicates AE pre-capture process is finished, app will take a picture.
                case WAIT_AE: {
                    if (isAETriggered) {
                        Integer aeState = result.get(SCaptureResult.CONTROL_AE_STATE);
                        if (null == aeState || // Legacy device might have null AE_STATE. However, this should not be happened as we skip triggerAE() for legacy device
                                SCaptureResult.CONTROL_AE_STATE_CONVERGED == aeState ||
                                SCaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED == aeState ||
                                SCaptureResult.CONTROL_AE_STATE_LOCKED == aeState) {
                            captureBurst();
                            isAETriggered = false;
                        }
                    }
                    break;
                }
            }
        }
    };

    @Override
    public void onPause() {
        setState(CAMERA_STATE.CLOSING);

        deinitProcessor();
        setOrientationListener(false);
        stopBackgroundThread();

        closeCamera();

        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        setState(CAMERA_STATE.IDLE);

        startBackgroundThread();

        // initialize SCamera
        mSCamera = new SCamera();
        try {
            mSCamera.initialize(this);
        } catch (SsdkUnsupportedException e) {
            showAlertDialog("Fail to initialize SCamera.", true);
            return;
        }

        setOrientationListener(true);

        if (!checkRequiredFeatures()) return;
        createProcessor();
        createUI();
        openCamera();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gif);
    }

    /**
     * Capture a series of images
     */
    private void captureBurst() {
        if (getState() == CAMERA_STATE.CLOSING)
            return;

        clearInputBitmaps();
        mJpegOrientation = getJpegOrientation();

        //update gif size according to the jpeg orientation
        {
            SCameraProcessorParameter parameter = mProcessor.getParameters();

            if(mJpegOrientation == 0 || mJpegOrientation == 180) parameter.set(SCameraProcessor.STILL_SIZE, new Size(mImageReader.getWidth(), mImageReader.getHeight()));
            else  parameter.set(SCameraProcessor.STILL_SIZE, new Size(mImageReader.getHeight(), mImageReader.getWidth()));
            mProcessor.setParameters(parameter);
        }

        numberOfImageCaptured = 0;
        numberOfImageProcessed = 0;

        // make sure output path is exist
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath() + "/Camera/");
        if (!dir.exists()) dir.mkdirs();

        mOutputFileName = dir.getAbsolutePath() + createFileName() + "_gif.gif";

        try {
            List<SCaptureRequest> captureRequestList = new ArrayList<>();

            mCaptureBuilder.removeTarget(mImageReader.getSurface());
            mCaptureBuilder.removeTarget(mPreviewSurface);

            for (int i = 0; i < NUM_OF_FRAME * 4; i++) { // 1:4 jpeg:preview
                if (i % 4 == 0) {
                    mCaptureBuilder.addTarget(mImageReader.getSurface());
                    mCaptureBuilder.addTarget(mPreviewSurface);
                } else {
                    mCaptureBuilder.removeTarget(mImageReader.getSurface());
                    mCaptureBuilder.addTarget(mPreviewSurface);
                }
                captureRequestList.add(mCaptureBuilder.build());
            }

            mSCameraSession.captureBurst(captureRequestList, new SCameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(SCameraCaptureSession session,
                                               SCaptureRequest request,
                                               STotalCaptureResult result) {
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;

                    numberOfImageCaptured++;
                    Log.v(TAG, "#" + numberOfImageCaptured / 4 + " captured so far.");

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            mNumImagesTextView.setText("Captured: " + numberOfImageCaptured / 4 + "/" + NUM_OF_FRAME);
                        }
                    });
                }

                @Override
                public void onCaptureFailed(SCameraCaptureSession session, SCaptureRequest request, SCaptureFailure failure) {
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;
                    showAlertDialog("JPEG Capture failed.", false);
                    unlockAF();
                }
            }, mBackgroundHandler); // This listener is called when the capture is completed.
            setState(CAMERA_STATE.TAKE_PICTURE);
        } catch (CameraAccessException e) {
            Log.e(TAG, "captureBurst exception : " + e.getMessage());
            showAlertDialog("Fail to capture images." + e.getMessage(), true);
        }
    }

    private boolean checkRequiredFeatures() {
        try {
            mCameraId = null;
            for (String id : mSCamera.getSCameraManager().getCameraIdList()) {
                SCameraCharacteristics cameraCharacteristics = mSCamera.getSCameraManager().getCameraCharacteristics(id);
                if (cameraCharacteristics.get(SCameraCharacteristics.LENS_FACING) == SCameraCharacteristics.LENS_FACING_BACK) {
                    mCameraId = id;
                    break;
                }
            }

            if (mCameraId == null) {
                showAlertDialog("No back-facing camera exist.", true);
                return false;
            }

            mCharacteristics = mSCamera.getSCameraManager().getCameraCharacteristics(mCameraId);

            if (!contains(mCharacteristics.get(SCameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), SCameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                showAlertDialog("Required AF mode is not supported.", true);
                return false;
            }

            if (!mSCamera.isFeatureEnabled(SCamera.SCAMERA_PROCESSOR)) {
                showAlertDialog("This device does not support SCamera Processor feature.", true);
                return false;
            }

            SCameraProcessorManager processorManager = mSCamera.getSCameraProcessorManager();
            if (!processorManager.isProcessorAvailable(SCameraProcessorManager.PROCESSOR_TYPE_GIF)) {
                showAlertDialog("This device does not support GIF Processor.", true);
                return false;
            }

        } catch (CameraAccessException e) {
            showAlertDialog("Cannot access the camera.", true);
            Log.e(TAG, "Cannot access the camera.", e);
            return false;
        }

        return true;
    }

    private void clearInputBitmaps() {
        mInputBitmapList.clear();
    }

    /**
     * Closes a camera and release resources.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();

            if (mSCameraSession != null) {
                mSCameraSession.close();
                mSCameraSession = null;
            }

            if (mSCameraDevice != null) {
                mSCameraDevice.close();
                mSCameraDevice = null;
            }

            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }

            mSCameraManager = null;
            mSCamera = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else {
            matrix.postRotate(90 * rotation, centerX, centerY);
        }

        mTextureView.setTransform(matrix);
        mTextureView.getSurfaceTexture().setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
    }

    private boolean contains(final int[] array, final int key) {
        for (final int i : array) {
            if (i == key) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates file name based on current time.
     */
    private String createFileName() {
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTimeZone(TimeZone.getDefault());
        long dateTaken = calendar.getTimeInMillis();

        return DateFormat.format("yyyyMMdd_kkmmss", dateTaken).toString();
    }

    /**
     * Create a {@link com.samsung.android.sdk.camera.SCameraCaptureSession} for preview.
     */
    private void createPreviewSession() {
        if (null == mSCamera
                || null == mSCameraDevice
                || null == mSCameraManager
                || null == mPreviewSize
                || !mTextureView.isAvailable())
            return;

        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();

            // Set default buffer size to camera preview size.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            mPreviewSurface = new Surface(texture);

            // Creates SCaptureRequest.Builder for preview with output target.
            mPreviewBuilder = mSCameraDevice.createCaptureRequest(SCameraDevice.TEMPLATE_PREVIEW);
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AF_MODE, SCaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mPreviewBuilder.addTarget(mPreviewSurface);

            mCaptureBuilder = mSCameraDevice.createCaptureRequest(SCameraDevice.TEMPLATE_STILL_CAPTURE);
            mCaptureBuilder.set(SCaptureRequest.CONTROL_AF_MODE, SCaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // Enable Phase AF, if device supports it.
            if (mCharacteristics.getKeys().contains(SCameraCharacteristics.PHASE_AF_INFO_AVAILABLE) &&
                    mCharacteristics.get(SCameraCharacteristics.PHASE_AF_INFO_AVAILABLE)) {
                mPreviewBuilder.set(SCaptureRequest.PHASE_AF_MODE, SCaptureRequest.PHASE_AF_MODE_ON);
                mCaptureBuilder.set(SCaptureRequest.PHASE_AF_MODE, SCaptureRequest.PHASE_AF_MODE_ON);
            }

            // Creates a SCameraCaptureSession here.
            List<Surface> outputSurface = Arrays.asList(mPreviewSurface, mImageReader.getSurface());
            mSCameraDevice.createCaptureSession(outputSurface, new SCameraCaptureSession.StateCallback() {
                @Override
                public void onConfigureFailed(SCameraCaptureSession sCameraCaptureSession) {
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;
                    showAlertDialog("Fail to create camera session.", true);
                }

                @Override
                public void onConfigured(SCameraCaptureSession sCameraCaptureSession) {
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;
                    mSCameraSession = sCameraCaptureSession;
                    startPreview();
                    setState(CAMERA_STATE.PREVIEW);
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            showAlertDialog("Fail to create session. " + e.getMessage(), true);
        }
    }

    private void createProcessor() {
        SCameraProcessorManager processorManager = mSCamera.getSCameraProcessorManager();

        mProcessor = processorManager.createProcessor(SCameraProcessorManager.PROCESSOR_TYPE_GIF);
    }

    /**
     * Prepares an UI, like button, dialog, etc.
     */
    private void createUI() {
        findViewById(R.id.picture).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // take picture is only works under preview state. lock af first to take gif picture.
                if (getState() == CAMERA_STATE.PREVIEW)
                    lockAF();
            }
        });

        mTextureView = (AutoFitTextureView) findViewById(R.id.texture);
        mNumImagesTextView = (TextView) findViewById(R.id.numImagesText);

        // Set SurfaceTextureListener that handle life cycle of TextureView
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                // "onSurfaceTextureAvailable" is called, which means that SCameraCaptureSession is not created.
                // We need to configure transform for TextureView and crate SCameraCaptureSession.
                configureTransform(width, height);
                createPreviewSession();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // SurfaceTexture size changed, we need to configure transform for TextureView, again.
                configureTransform(width, height);
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    private Bitmap decodeToBitmap(Image image, int orientation) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.capacity()];
        buffer.get(bytes);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);

        if(orientation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(orientation);

            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        }
        return bitmap;
    }

    private void deinitProcessor() {
        if (mProcessor != null) {
            mProcessor.deinitialize();
            mProcessor.close();
            mProcessor = null;
        }
    }

    /**
     * Returns required orientation that the jpeg picture needs to be rotated to be displayed upright.
     */
    private int getJpegOrientation() {
        int degrees = mLastOrientation;

        if (mCharacteristics.get(SCameraCharacteristics.LENS_FACING) == SCameraCharacteristics.LENS_FACING_FRONT) {
            degrees = -degrees;
        }

        return (mCharacteristics.get(SCameraCharacteristics.SENSOR_ORIENTATION) + degrees + 360) % 360;
    }

    private CAMERA_STATE getState() {
        return mState;
    }

    private synchronized void setState(CAMERA_STATE state) {
        mState = state;
    }

    private void initProcessor() {
        SCameraProcessorParameter parameter = mProcessor.getParameters();

        parameter.set(SCameraGifProcessor.GIF_FRAME_DURATION, 100 /* 100ms */);

        mProcessor.setParameters(parameter);
        mProcessor.initialize();
        mProcessor.setEventCallback(mProcessorCallback, mBackgroundHandler);
    }

    /**
     * Starts AF process by triggering {@link com.samsung.android.sdk.camera.SCaptureRequest#CONTROL_AF_TRIGGER_START}.
     */
    private void lockAF() {
        try {
            setState(CAMERA_STATE.WAIT_AF);
            isAFTriggered = false;

            // Set AF trigger to SCaptureRequest.Builder
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AF_TRIGGER, SCaptureRequest.CONTROL_AF_TRIGGER_START);

            // App should send AF triggered request for only a single capture.
            mSCameraSession.capture(mPreviewBuilder.build(), new SCameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(SCameraCaptureSession session, SCaptureRequest request, STotalCaptureResult result) {
                    isAFTriggered = true;
                }
            }, mBackgroundHandler);
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AF_TRIGGER, SCaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        } catch (CameraAccessException e) {
            showAlertDialog("Fail to trigger AF", true);
        }
    }

    /**
     * Opens a {@link com.samsung.android.sdk.camera.SCameraDevice}.
     */
    private void openCamera() {

        try {
            if (!mCameraOpenCloseLock.tryAcquire(3000, TimeUnit.MILLISECONDS)) {
                showAlertDialog("Time out waiting to lock camera opening.", true);
            }

            mSCameraManager = mSCamera.getSCameraManager();

            // Acquires camera characteristics
            SCameraCharacteristics characteristics = mSCameraManager.getCameraCharacteristics(mCameraId);

            // Acquires ae step from device. Smallest step by which the exposure compensation can be changed.
            //mDeviceAeStep = characteristics.get(SCameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();

            StreamConfigurationMap streamConfigurationMap = characteristics.get(SCameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // Acquires supported preview size list that supports SurfaceTexture
            mPreviewSize = streamConfigurationMap.getOutputSizes(SurfaceTexture.class)[0];
            for (Size option : streamConfigurationMap.getOutputSizes(SurfaceTexture.class)) {
                // Find maximum preview size that is not larger than MAX_PREVIEW_WIDTH/MAX_PREVIEW_HEIGHT
                int areaCurrent = Math.abs((mPreviewSize.getWidth() * mPreviewSize.getHeight()) - (MAX_PREVIEW_WIDTH * MAX_PREVIEW_HEIGHT));
                int areaNext = Math.abs((option.getWidth() * option.getHeight()) - (MAX_PREVIEW_WIDTH * MAX_PREVIEW_HEIGHT));

                if (areaCurrent > areaNext) mPreviewSize = option;
            }

            // Acquires supported size for JPEG format
            Size[] jpegSizeList = null;
            jpegSizeList = streamConfigurationMap.getOutputSizes(ImageFormat.JPEG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 0 == jpegSizeList.length) {
                // If device has 'SCameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE' getOutputSizes can return zero size list
                // for a format value in getOutputFormats.
                jpegSizeList = streamConfigurationMap.getHighResolutionOutputSizes(ImageFormat.JPEG);
            }
            Size jpegSize = jpegSizeList[0];

            for (Size size : jpegSizeList) {
                //find minimum jpeg size with gif output aspect ratio
                if (Float.compare((float) size.getWidth() / size.getHeight(), (float) GIF_WIDTH / GIF_HEIGHT) == 0 &&
                        size.getWidth() >= GIF_WIDTH && size.getWidth() <= jpegSize.getWidth()) {
                    jpegSize = size;
                }
            }
            Log.v(TAG, "Jpeg size = " + jpegSize.toString());

            // Configures an ImageReader
            mImageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, NUM_OF_FRAME + 1);
            mImageReader.setOnImageAvailableListener(mImageAvailableListener, mBackgroundHandler);

            // Set the aspect ratio to TextureView
            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(
                        mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(
                        mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }

            // Initialize gif processor
            initProcessor();

            // Opening the camera device here
            mSCameraManager.openCamera(mCameraId, new SCameraDevice.StateCallback() {
                @Override
                public void onDisconnected(SCameraDevice sCameraDevice) {
                    mCameraOpenCloseLock.release();
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;
                    showAlertDialog("Camera disconnected.", true);
                }

                @Override
                public void onError(SCameraDevice sCameraDevice, int i) {
                    mCameraOpenCloseLock.release();
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;
                    showAlertDialog("Error while camera open.", true);
                }

                public void onOpened(SCameraDevice sCameraDevice) {
                    mCameraOpenCloseLock.release();
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;
                    mSCameraDevice = sCameraDevice;
                    createPreviewSession();
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            showAlertDialog("Cannot open the camera.", true);
            Log.e(TAG, "Cannot open the camera.", e);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Enable/Disable an orientation listener.
     */
    private void setOrientationListener(boolean isEnable) {
        if (mOrientationListener == null) {

            mOrientationListener = new OrientationEventListener(this) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == ORIENTATION_UNKNOWN) return;
                    mLastOrientation = (orientation + 45) / 90 * 90;
                }
            };
        }

        if (isEnable) {
            mOrientationListener.enable();
        } else {
            mOrientationListener.disable();
        }
    }

    /**
     * Shows alert dialog.
     */
    private void showAlertDialog(String message, final boolean finishActivity) {
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setMessage(message)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("Alert")
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (finishActivity) finish();
                    }
                }).setCancelable(false);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                dialog.show();
            }
        });
    }

    /**
     * Starts back ground thread that callback from camera will posted.
     */
    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("Background Thread");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    /**
     * Starts a preview.
     */
    private void startPreview() {
        try {
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AE_LOCK, false);
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
            mSCameraSession.setRepeatingRequest(mPreviewBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            showAlertDialog("Fail to start preview.", true);
        }
    }

    /**
     * Stops back ground thread.
     */
    private void stopBackgroundThread() {
        if (mBackgroundHandlerThread != null) {
            mBackgroundHandlerThread.quitSafely();
            try {
                mBackgroundHandlerThread.join();
                mBackgroundHandlerThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Starts AE pre-capture
     */
    private void triggerAE() {
        try {
            setState(CAMERA_STATE.WAIT_AE);
            isAETriggered = false;

            mPreviewBuilder.set(SCaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, SCaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            // App should send AE triggered request for only a single capture.
            mSCameraSession.capture(mPreviewBuilder.build(), new SCameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(SCameraCaptureSession session, SCaptureRequest request, STotalCaptureResult result) {
                    isAETriggered = true;
                }
            }, mBackgroundHandler);
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, SCaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        } catch (CameraAccessException e) {
            showAlertDialog("Fail to trigger AE", true);
        }
    }

    /**
     * Unlock AF.
     */
    private void unlockAF() {
        // Triggers CONTROL_AF_TRIGGER_CANCEL to return to initial AF state.
        try {
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AF_TRIGGER, SCaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            mSCameraSession.capture(mPreviewBuilder.build(), new SCameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(SCameraCaptureSession session, SCaptureRequest request, STotalCaptureResult result) {
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;
                    setState(CAMERA_STATE.PREVIEW);
                }
            }, mBackgroundHandler);
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AF_TRIGGER, SCaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        } catch (CameraAccessException e) {
            showAlertDialog("Fail to cancel AF", false);
        }
    }

    private enum CAMERA_STATE {
        IDLE, PREVIEW, WAIT_AF, WAIT_AE, TAKE_PICTURE, CLOSING
    }
}

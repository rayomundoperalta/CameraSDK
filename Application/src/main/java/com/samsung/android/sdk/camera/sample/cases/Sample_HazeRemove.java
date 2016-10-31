package com.samsung.android.sdk.camera.sample.cases;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
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
import android.util.Range;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
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
import com.samsung.android.sdk.camera.processor.SCameraHazeRemoveProcessor;
import com.samsung.android.sdk.camera.processor.SCameraProcessorManager;
import com.samsung.android.sdk.camera.processor.SCameraProcessorParameter;
import com.samsung.android.sdk.camera.sample.R;
import com.samsung.android.sdk.camera.sample.cases.util.AutoFitTextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Sample_HazeRemove extends Activity {
    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Sample_Haze_Removal";
    /**
     * Maximum preview width app will use.
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;
    /**
     * Maximum preview height app will use.
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    private SCamera mSCamera;
    private SCameraManager mSCameraManager;
    private SCameraDevice mSCameraDevice;
    private SCameraCaptureSession mSCameraSession;
    private SCaptureRequest.Builder mPreviewBuilder;
    private SCaptureRequest.Builder mCaptureBuilder;
    /**
     * ID of the current {@link SCameraDevice}.
     */
    private String mCameraId;
    private SCameraCharacteristics mCharacteristics;
    /**
     * Haze removal processor
     */
    private SCameraHazeRemoveProcessor mProcessor;
    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;
    private ImageReader mImageReader;
    private ImageSaver mImageSaver = new ImageSaver();
    private Size mPreviewSize;
    /**
     * Captured image data
     */
    private Image mImage;

    private SeekBar mStrengthSeekBar;
    private Range<Integer> mStrengthRange;

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
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    /**
     * True if {@link SCaptureRequest#CONTROL_AF_TRIGGER} is triggered.
     */
    private boolean isAFTriggered;
    /**
     * True if {@link SCaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER} is triggered.
     */
    private boolean isAETriggered;
    /**
     * Current app state.
     */
    private CAMERA_STATE mState = CAMERA_STATE.IDLE;
    /**
     * A {@link SCameraCaptureSession.CaptureCallback} for {@link SCameraCaptureSession#setRepeatingRequest(SCaptureRequest, SCameraCaptureSession.CaptureCallback, Handler)}
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
                                takePicture();
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
                            takePicture();
                            isAETriggered = false;
                        }
                    }
                    break;
                }
            }
        }
    };
    /**
     * A {@link ImageReader.OnImageAvailableListener} for still capture.
     */
    private ImageReader.OnImageAvailableListener mImageCallback = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            if (getState() == CAMERA_STATE.CLOSING)
                return;

            mImage = reader.acquireNextImage();

            // process haze removal effect using image data
            mProcessor.requestProcess(mImage);
        }
    };
    /**
     * Callback to receive result from haze removal processor.
     */
    private SCameraHazeRemoveProcessor.EventCallback mProcessorCallback = new SCameraHazeRemoveProcessor.EventCallback() {

        /**
         * Called when error occurred.
         */
        @Override
        public void onError(int code) {
            if (getState() == CAMERA_STATE.CLOSING)
                return;

            StringBuilder builder = new StringBuilder();
            builder.append("Fail to create result: ");

            switch (code) {
                case SCameraHazeRemoveProcessor.NATIVE_PROCESSOR_MSG_DECODING_FAIL: {
                    builder.append("decoding fail");
                    break;
                }

                case SCameraHazeRemoveProcessor.NATIVE_PROCESSOR_MSG_ENCODING_FAIL: {
                    builder.append("encoding fail");
                    break;
                }

                case SCameraHazeRemoveProcessor.NATIVE_PROCESSOR_MSG_PROCESSING_FAIL: {
                    builder.append("processing fail");
                    break;
                }

                case SCameraHazeRemoveProcessor.NATIVE_PROCESSOR_MSG_UNKNOWN_ERROR: {
                    builder.append("unknown error");
                    break;
                }
            }
            showAlertDialog(builder.toString(), false);
            unlockAF();

            clearInputImage();
        }

        /**
         * Called when {@link SCameraHazeRemoveProcessor#requestProcess(Image)} is finished.
         */
        @Override
        public void onProcessCompleted(Image result) {
            if (getState() == CAMERA_STATE.CLOSING)
                return;

            // decode result image to bitmap
            ByteBuffer jpegData = result.getPlanes()[0].getBuffer();
            Bitmap resultBitmap = decodeToBitmap(jpegData, 4, 0);
            // make crop image to show detailed haze remove result
            Bitmap resultCrop = decodeToBitmap(jpegData, 1, 600);

            // save result image to file
            mImageSaver.save(result, createFileName() + "_haze.jpg");

            // decode input image
            jpegData = mImage.getPlanes()[0].getBuffer();
            Bitmap inputCrop = decodeToBitmap(jpegData, 1, 600);

            // show result dialog
            showResultDialog(resultBitmap, inputCrop, resultCrop);
            unlockAF();

            clearInputImage();
        }
    };

    @Override
    public void onPause() {
        setState(CAMERA_STATE.CLOSING);

        setOrientationListener(false);

        stopBackgroundThread();

        deinitProcessor();
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
        setContentView(R.layout.activity_haze_remove);
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
            if (!processorManager.isProcessorAvailable(SCameraProcessorManager.PROCESSOR_TYPE_HAZE_REMOVE)) {
                showAlertDialog("This device does not support Haze removal Processor.", true);
                return false;
            }

        } catch (CameraAccessException e) {
            showAlertDialog("Cannot access the camera.", true);
            Log.e(TAG, "Cannot access the camera.", e);
            return false;
        }

        return true;
    }

    private void clearInputImage() {
        if (mImage != null) {
            mImage.close();
            mImage = null;
        }
    }

    /**
     * Closes a camera and release resources.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();

            stopPreview();

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
     * Create a {@link SCameraCaptureSession} for preview.
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

            // set a surface of UI preview, must be called before {@link com.samsung.android.sdk.camera.processor.SCameraHazeRemoveProcessor#initialize() }
            mProcessor.setOutputSurface(new Surface(texture));

            // retrieving a surface of camera preview, this must be set to preview request.
            Surface cameraSurface = mProcessor.getInputSurface();

            // Creates SCaptureRequest.Builder for preview with output target.
            mPreviewBuilder = mSCameraDevice.createCaptureRequest(SCameraDevice.TEMPLATE_PREVIEW);
            mPreviewBuilder.set(SCaptureRequest.CONTROL_AF_MODE, SCaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mPreviewBuilder.addTarget(cameraSurface);

            // Create SCaptureRequest.Builder for still capture
            mCaptureBuilder = mSCameraDevice.createCaptureRequest(SCameraDevice.TEMPLATE_STILL_CAPTURE);
            mCaptureBuilder.set(SCaptureRequest.CONTROL_AF_MODE, SCaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mCaptureBuilder.addTarget(mImageReader.getSurface());

            // Enable Phase AF, if device supports it.
            if (mCharacteristics.getKeys().contains(SCameraCharacteristics.PHASE_AF_INFO_AVAILABLE) &&
                    mCharacteristics.get(SCameraCharacteristics.PHASE_AF_INFO_AVAILABLE)) {
                mPreviewBuilder.set(SCaptureRequest.PHASE_AF_MODE, SCaptureRequest.PHASE_AF_MODE_ON);
                mCaptureBuilder.set(SCaptureRequest.PHASE_AF_MODE, SCaptureRequest.PHASE_AF_MODE_ON);
            }

            // Creates a SCameraCaptureSession here.
            List<Surface> outputSurface = Arrays.asList(cameraSurface, mImageReader.getSurface());
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
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            showAlertDialog("Fail to create session. " + e.getMessage(), true);
        }
    }

    private void createProcessor() {
        SCameraProcessorManager processorManager = mSCamera.getSCameraProcessorManager();

        mProcessor = processorManager.createProcessor(SCameraProcessorManager.PROCESSOR_TYPE_HAZE_REMOVE);
    }

    /**
     * Prepares an UI, like button, dialog, etc.
     */
    private void createUI() {
        findViewById(R.id.picture).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // take picture is only works under preview state. lock af first to take picture.
                if (getState() == CAMERA_STATE.PREVIEW)
                    lockAF();
            }
        });

        mStrengthSeekBar = (SeekBar) findViewById(R.id.strength);

        mTextureView = (AutoFitTextureView) findViewById(R.id.texture);

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

    private Bitmap decodeToBitmap(ByteBuffer jpegData, int sampleSize, int cropWidth) {
        byte[] jpegDataArray = new byte[jpegData.remaining()];
        jpegData.get(jpegDataArray);
        jpegData.rewind();

        BitmapFactory.Options option = new BitmapFactory.Options();
        option.inSampleSize = sampleSize;

        if (cropWidth == 0) {
            return BitmapFactory.decodeByteArray(jpegDataArray, 0, jpegDataArray.length, option);
        }

        Bitmap bitmap = null;
        try {
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(jpegDataArray, 0, jpegDataArray.length, true);

            int cropHeight = cropWidth * decoder.getHeight() / decoder.getWidth();
            Rect cropRect = new Rect(decoder.getWidth() / 2 - cropWidth,
                    decoder.getHeight() / 2 - cropHeight,
                    decoder.getWidth() / 2 + cropWidth,
                    decoder.getHeight() / 2 + cropHeight);

            bitmap = decoder.decodeRegion(cropRect, option);
        } catch (IOException e) {
            e.printStackTrace();
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

        parameter.set(SCameraHazeRemoveProcessor.STILL_INPUT_FORMAT, ImageFormat.JPEG);
        parameter.set(SCameraHazeRemoveProcessor.STILL_OUTPUT_FORMAT, ImageFormat.JPEG);
        parameter.set(SCameraHazeRemoveProcessor.STILL_SIZE, new Size(mImageReader.getWidth(), mImageReader.getHeight()));
        parameter.set(SCameraHazeRemoveProcessor.STREAM_SIZE, mPreviewSize);
        parameter.set(SCameraHazeRemoveProcessor.HAZE_REMOVE_STRENGTH, parameter.get(SCameraHazeRemoveProcessor.HAZE_REMOVE_STRENGTH_RANGE).getUpper());

        mProcessor.setParameters(parameter);
        mProcessor.initialize();
        mProcessor.setEventCallback(mProcessorCallback, mBackgroundHandler);

        mStrengthRange = parameter.get(SCameraHazeRemoveProcessor.HAZE_REMOVE_STRENGTH_RANGE);
        mStrengthSeekBar.setMax(mStrengthRange.getUpper() - mStrengthRange.getLower());
        mStrengthSeekBar.setProgress((mStrengthRange.getUpper() - mStrengthRange.getLower()) / 2);

        mStrengthSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                Log.e(TAG, String.format("Strength --> %d", progress + mStrengthRange.getLower()));

                SCameraProcessorParameter param = mProcessor.getParameters();
                param.set(SCameraHazeRemoveProcessor.HAZE_REMOVE_STRENGTH, progress + mStrengthRange.getLower());
                mProcessor.setParameters(param);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    /**
     * Starts AF process by triggering {@link SCaptureRequest#CONTROL_AF_TRIGGER_START}.
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
     * Opens a {@link SCameraDevice}.
     */
    private void openCamera() {
        try {
            if (!mCameraOpenCloseLock.tryAcquire(3000, TimeUnit.MILLISECONDS)) {
                showAlertDialog("Time out waiting to lock camera opening.", true);
            }

            mSCameraManager = mSCamera.getSCameraManager();

            // Acquires camera characteristics
            SCameraCharacteristics characteristics = mSCameraManager.getCameraCharacteristics(mCameraId);
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

            // Configures an ImageReader
            mImageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 1);
            mImageReader.setOnImageAvailableListener(mImageCallback, mBackgroundHandler);

            // Set the aspect ratio to TextureView
            int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(
                        mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(
                        mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }

            // Initialize haze removal processor
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
     * Shows haze removal result into dialog.
     */
    private void showResultDialog(Object... args) {
        final AlertDialog.Builder dialog = new AlertDialog.Builder(this);

        View dialogView = getLayoutInflater().inflate(R.layout.result_dialog_haze, null);
        ImageView resultImage = (ImageView) dialogView.findViewById(R.id.resultImage);
        resultImage.setImageBitmap((Bitmap) args[0]);

        ImageView inputImageCrop = (ImageView) dialogView.findViewById(R.id.inputImageCrop);
        inputImageCrop.setImageBitmap((Bitmap) args[1]);

        ImageView resultImageCrop = (ImageView) dialogView.findViewById(R.id.resultImageCrop);
        resultImageCrop.setImageBitmap((Bitmap) args[2]);

        dialog.setView(dialogView)
                .setTitle("Capture result")
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });

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
            // Starts displaying the preview.
            mSCameraSession.setRepeatingRequest(mPreviewBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
            setState(CAMERA_STATE.PREVIEW);
            mProcessor.startStreamProcessing();
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
     * Stop a preview.
     */
    private void stopPreview() {
        try {
            if (mSCameraSession != null)
                mSCameraSession.stopRepeating();

            if (mProcessor != null && getState() == CAMERA_STATE.PREVIEW)
                mProcessor.stopStreamProcessing();
        } catch (CameraAccessException e) {
            showAlertDialog("Fail to stop preview.", true);
        }
    }

    /**
     * Take picture.
     */
    private void takePicture() {
        if (getState() == CAMERA_STATE.CLOSING)
            return;

        try {
            // Sets orientation
            mCaptureBuilder.set(SCaptureRequest.JPEG_ORIENTATION, getJpegOrientation());

            mSCameraSession.capture(mCaptureBuilder.build(), new SCameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(SCameraCaptureSession session, SCaptureRequest request, SCaptureFailure failure) {
                    if (getState() == CAMERA_STATE.CLOSING)
                        return;
                    showAlertDialog("JPEG Capture failed.", false);
                    unlockAF();
                }
            }, mBackgroundHandler);
            setState(CAMERA_STATE.TAKE_PICTURE);
        } catch (CameraAccessException e) {
            showAlertDialog("Fail to start preview.", true);
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

    /**
     * Saves {@link Image} to file.
     */
    private class ImageSaver {
        void save(final Image image, String filename) {

            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath() + "/Camera/");
            if (!dir.exists()) dir.mkdirs();

            final File file = new File(dir, filename);

            mBackgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                        if (null != output) {
                            try {
                                output.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    MediaScannerConnection.scanFile(Sample_HazeRemove.this,
                            new String[]{file.getAbsolutePath()}, null,
                            new MediaScannerConnection.OnScanCompletedListener() {
                                public void onScanCompleted(String path, Uri uri) {
                                    Log.i(TAG, "ExternalStorage Scanned " + path + "-> uri=" + uri);
                                }
                            });

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(Sample_HazeRemove.this, "Saved: " + file.getName(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
        }
    }
}

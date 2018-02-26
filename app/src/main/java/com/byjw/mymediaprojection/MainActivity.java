package com.byjw.mymediaprojection;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getName();
    private static final int REQUEST_CODE = 100;
    private static int IMAGES_PRODUCED;
    private static final String SCREENCAP_NAME = "screencap";
    private static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static MediaProjection mediaProjection;

    private MediaProjectionManager mediaProjectionManager;
    private ImageReader imageReader;
    private Handler handler;
    private Display display;
    private VirtualDisplay virtualDisplay;
    private int density;
    private int width;
    private int height;
    private int rotation;
    private OrientationChangeCallback orientationChangeCallback;

    private final String MAIN_PATH = android.os.Environment.getExternalStorageDirectory().toString() + "/Jungwoon4/";

    final int MY_PHONE_STATE_CODE = 1;

    @OnClick(R.id.startButton)
    void startButton() {
        startProjection();
    }

    @OnClick(R.id.stopButton)
    void stopButton() {
        stopProjection();
    }

    /****************************************** Activity Lifecycle methods ************************/
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        // MediaProjectionManager 인스턴스 생성
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        // start capture handling thread
        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                handler = new Handler();
                Looper.loop();
            }
        }.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 쓰기 권한이 없으면 Runtime Permission 요청
        if (!checkPermissions()) requestPermissions();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE) {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);

            if (mediaProjection != null) {

                File storeDirectory = new File(MAIN_PATH);
                if (!storeDirectory.exists()) {
                    boolean success = storeDirectory.mkdirs();
                    if (!success) {
                        Log.e(TAG, "failed to create file storage directory.");
                        return;
                    }
                }

                // display metrics
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                density = metrics.densityDpi;
                display = getWindowManager().getDefaultDisplay();

                // create virtual display depending on device width / height
                createVirtualDisplay();

                // register orientation change callback
                orientationChangeCallback = new OrientationChangeCallback(this);
                if (orientationChangeCallback.canDetectOrientation()) {
                    orientationChangeCallback.enable();
                }

                // MediaProjection의 상태 변경시 해당 알림을 수신하기 위한 리스너
                mediaProjection.registerCallback(new MediaProjectionStopCallback(), handler);
            }
        }
    }

    /****************************************** Image Render **************************************/

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            FileOutputStream fileOutputStream = null;
            Bitmap bitmap = null;

            try {
                // 최신 이미지를 가져오는 부분
                image = reader.acquireLatestImage();
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();

                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;

                    // create bitmap
                    // **여기서 width와 height를 조절하면 이미지 자르기 가능
                    bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer); // 버퍼로부터 이미지를 가져와 bitmap으로 만듭니다

                    // 위에서 생성한 bitmap을 png 파일로 만들어 줍니다
                    fileOutputStream = new FileOutputStream(MAIN_PATH + IMAGES_PRODUCED + ".png");
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fileOutputStream);

                    IMAGES_PRODUCED++;
                    Log.e(TAG, "captured image: " + IMAGES_PRODUCED);
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    }
                    catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }

                if (bitmap != null) {
                    bitmap.recycle();
                }

                if (image != null) {
                    image.close();
                }
            }
        }
    }


    // 화면 전환일때의 처리
    private class OrientationChangeCallback extends OrientationEventListener {
        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = display.getRotation();
            if (rotation != MainActivity.this.rotation) {
                MainActivity.this.rotation = rotation;
                try {
                    if (virtualDisplay != null) virtualDisplay.release();
                    if (imageReader != null) imageReader.setOnImageAvailableListener(null, null);

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.e("ScreenCapture", "stopping projection.");
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (virtualDisplay != null) virtualDisplay.release();
                    if (imageReader != null) imageReader.setOnImageAvailableListener(null, null);
                    if (orientationChangeCallback != null) orientationChangeCallback.disable();
                    mediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                }
            });
        }
    }

    /****************************************** UI Widget Callbacks *******************************/
    private void startProjection() {
        // createScreenCaptureIntent()를 통해서 화면 캡처가 시작이 됨
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE);
    }

    private void stopProjection() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mediaProjection != null) {
                    mediaProjection.stop();
                }
            }
        });
    }

    /****************************************** 가상 디스플레이 만드는 부분 ****************/
    private void createVirtualDisplay() {
        Point size = new Point();
        display.getSize(size);
        width = size.x;
        height = size.y;

        // imageReader로부터 캡처하기 시작
        // ImageReader newInstance (int width,
        //      int height,
        //      int format,
        //      int maxImages)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        // 실제 화면캡처가 시작 되는 곳
        // SCREENCAP_NAME = 가상디스플레이의 이름입니다. empty가 될 수 없습니다.
        // width = 가상디스플레이의 픽셀 너비입니다. 0보다 커야됩니다.
        // height = 가상디스플레이의 픽셀 높이입니다. 0보다 커야됩니다. (**여기서 크기를 줄이면 이미지가 줄어듭니다)
        // density = 가상디스플레이의 밀도입니다. 0보다 커야됩니다.

        // VIRTUAL_DISPLAY_FLAGS : DisplayManager의 플래그들
        // - DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC : 가상 디스플레이를 공공의 디스플레이로 만듭니다
        // - DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY : 자기 자신의 디스플레이 내용에 대해서만 가상 디스플레이를 만들고 VIRTUAL_DISPLAY_FLAG_PUBLIC와 함께 쓰입니다

        // imageReader.getSurfacee() : imageReader 클래스가 지정한 surface를 가져옵니다
        virtualDisplay = mediaProjection.createVirtualDisplay(SCREENCAP_NAME, width, height, density, VIRTUAL_DISPLAY_FLAGS, imageReader.getSurface(), null, handler);
        imageReader.setOnImageAvailableListener(new ImageAvailableListener(), handler);
    }

    /****************************************** Permissions ***************************************/
    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, MY_PHONE_STATE_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch(requestCode) {
            case MY_PHONE_STATE_CODE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Granted");
                }
                else {
                    Log.e(TAG, "permission fail");
                    finish();
                }
        }
    }
}

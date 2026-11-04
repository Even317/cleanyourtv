package com.x96.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MouseOverlay {
    private static final String TAG = "MouseOverlay";
    private static volatile MouseOverlay sInstance;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private CursorView mCursorView;
    private WindowManager.LayoutParams mParams;

    private float mX = 640f;
    private float mY = 360f;
    private float mScreenWidth = 1280f;
    private float mScreenHeight = 720f;
    private boolean mIsAdded = false;
    private boolean mIsVisible = false;

    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hideCursor();
        }
    };

    public static MouseOverlay init(Context context) {
        if (sInstance == null) {
            synchronized (MouseOverlay.class) {
                if (sInstance == null) {
                    sInstance = new MouseOverlay(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    public static MouseOverlay getInstance() {
        return sInstance;
    }

    private MouseOverlay(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        try {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                mScreenWidth = dm.widthPixels;
                mScreenHeight = dm.heightPixels;
            }
        } catch (Exception ignored) {}

        mX = mScreenWidth / 2f;
        mY = mScreenHeight / 2f;

        mHandler.post(this::initView);
    }

    private void initView() {
        if (mIsAdded) return;
        try {
            mCursorView = new CursorView(mContext);
            mCursorView.setVisibility(View.GONE);

            int windowType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            mParams = new WindowManager.LayoutParams(
                64, 64,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            );
            mParams.gravity = Gravity.TOP | Gravity.START;
            mParams.x = (int) mX;
            mParams.y = (int) mY;

            mWindowManager.addView(mCursorView, mParams);
            mIsAdded = true;
            Log.i(TAG, "MouseOverlay view attached successfully at " + mX + "x" + mY);
        } catch (Exception e) {
            Log.e(TAG, "Failed to attach overlay window", e);
        }
    }

    public void move(float dx, float dy) {
        float sensitivity = 1.6f;
        mX = Math.max(0f, Math.min(mScreenWidth - 1f, mX + dx * sensitivity));
        mY = Math.max(0f, Math.min(mScreenHeight - 1f, mY + dy * sensitivity));

        mHandler.post(() -> {
            if (!mIsAdded) initView();
            if (mCursorView == null || !mIsAdded) return;

            mParams.x = (int) mX;
            mParams.y = (int) mY;

            if (!mIsVisible) {
                mCursorView.setVisibility(View.VISIBLE);
                mCursorView.setAlpha(1.0f);
                mIsVisible = true;
            }

            try {
                mWindowManager.updateViewLayout(mCursorView, mParams);
            } catch (Exception ignored) {}

            mHandler.removeCallbacks(mHideRunnable);
            mHandler.postDelayed(mHideRunnable, 4000);
        });
    }

    public void click() {
        mHandler.post(() -> {
            if (mCursorView != null && mIsVisible) {
                mCursorView.animate().scaleX(0.85f).scaleY(0.85f).setDuration(60)
                    .withEndAction(() -> {
                        if (mCursorView != null) {
                            mCursorView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
                        }
                    }).start();
            }
        });

        final int tapX = (int) mX;
        final int tapY = (int) mY;
        new Thread(() -> {
            sendFastCmd("tap " + tapX + " " + tapY);
        }).start();

        mHandler.removeCallbacks(mHideRunnable);
        mHandler.postDelayed(mHideRunnable, 4000);
    }

    public void rightClick() {
        new Thread(() -> {
            sendFastCmd("key 4"); // BACK
        }).start();
    }

    public void hideCursor() {
        mHandler.post(() -> {
            if (mCursorView != null && mIsVisible) {
                mCursorView.animate().alpha(0.0f).setDuration(250)
                    .withEndAction(() -> {
                        if (mCursorView != null) {
                            mCursorView.setVisibility(View.GONE);
                            mIsVisible = false;
                        }
                    }).start();
            }
        });
    }

    private void sendFastCmd(String cmd) {
        try (Socket s = new Socket("127.0.0.1", 9999);
             OutputStream os = s.getOutputStream()) {
            s.setTcpNoDelay(true);
            os.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (Exception e) {
            RootShell.getInstance().exec(cmd.split("\\s+"));
        }
    }

    private static class CursorView extends View {
        private final Path mPath = new Path();
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public CursorView(Context context) {
            super(context);

            mFillPaint.setColor(Color.WHITE);
            mFillPaint.setStyle(Paint.Style.FILL);
            mFillPaint.setShadowLayer(5.0f, 2.0f, 3.0f, 0x99000000);

            mStrokePaint.setColor(0xFF101010);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeWidth(2.6f);
            mStrokePaint.setStrokeJoin(Paint.Join.ROUND);

            // Clean crisp mouse cursor arrow, hotspot exactly at (0, 0)
            mPath.moveTo(2f, 2f);
            mPath.lineTo(2f, 32f);
            mPath.lineTo(9f, 25f);
            mPath.lineTo(16f, 37f);
            mPath.lineTo(20f, 35f);
            mPath.lineTo(13f, 23f);
            mPath.lineTo(23f, 23f);
            mPath.close();

            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawPath(mPath, mFillPaint);
            canvas.drawPath(mPath, mStrokePaint);
        }
    }
}

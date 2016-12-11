/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final String TAG = "SunshineWatchFace";

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        TextPaint mTextPaint;
        TextPaint mTimePaint;
        TextPaint mDatePaint;
        TextPaint mHighPaint;
        TextPaint mLowPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mCenterX;
        float mCenterY;

        private Rect mPeekCardBounds = new Rect();

        private GoogleApiClient mGoogleApiClient;
        private static final String WEARABLE_PATH = "/sunshine_watchface";
        private static final String HIGH_TEMP_KEY = "high_temp";
        private static final String LOW_TEMP_KEY = "low_temp";
        private static final String WEATHER_ID_KEY = "weather_id";
        private static final String ART_KEY = "art_key";
        private final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
        private String mHighTemp;
        private String mLowTemp;
        private Bitmap mWeatherArt;
        private Bitmap mWeatherArtMonochrome;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            mGoogleApiClient.connect();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setPeekOpacityMode(WatchFaceStyle.PEEK_OPACITY_MODE_OPAQUE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.background));

            mTextPaint = new TextPaint();
            mTextPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.digital_text));
            mTextPaint.setAntiAlias(true);

            mTimePaint = new TextPaint(mTextPaint);
            mTimePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));

            // Alpha of date and low temp, out of 255
            int alpha = 150;

            mDatePaint = new TextPaint(mTextPaint);
            mDatePaint.setTextAlign(Paint.Align.CENTER);
            mDatePaint.setAlpha(alpha);
            mDatePaint.setTextScaleX(0.8f);

            mHighPaint = new TextPaint(mTextPaint);
            mHighPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
            mHighPaint.setTextAlign(Paint.Align.CENTER);

            mLowPaint = new TextPaint(mTextPaint);
            mLowPaint.setAlpha(alpha);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected2: " + bundle);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended2: " + i);
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed2: " + connectionResult);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    Log.v("onDataChangedPath", item.getUri().getPath());
                    if (item.getUri().getPath().compareTo(WEARABLE_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        GetWeatherDataTask getWeatherDataTask = new GetWeatherDataTask(dataMap);
                        getWeatherDataTask.execute(0);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        private void updateUI(String highTemp, String lowTemp, Bitmap weatherArt) {
            Log.v("updateWatch", "highTemp: " + highTemp + ", lowTemp: " + lowTemp);
            mHighTemp = highTemp;
            mLowTemp = lowTemp;
            mWeatherArt = weatherArt;
            mWeatherArtMonochrome = toMonochrome(weatherArt);
            invalidate();
            mGoogleApiClient.connect();
        }


        /**
         * ASyncTask to load a bitmap from an asset. Using help from Android documentation
         * https://developer.android.com/training/displaying-bitmaps/process-bitmap.html and
         * https://developer.android.com/training/wearables/data-layer/assets.html
         */
        private class GetWeatherDataTask extends AsyncTask<Integer, Void, Bitmap> {
            private final Asset artAsset;
            private final String highTemp;
            private final String lowTemp;

            public GetWeatherDataTask(DataMap dataMap) {
                artAsset = dataMap.getAsset(ART_KEY);
                highTemp = dataMap.getString(HIGH_TEMP_KEY);
                lowTemp = dataMap.getString(LOW_TEMP_KEY);
            }

            protected Bitmap doInBackground(Integer... params) {
                if (artAsset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                ConnectionResult result =
                        mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!result.isSuccess()) {
                    return null;
                }
                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, artAsset).await().getInputStream();
                mGoogleApiClient.disconnect();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown Asset.");
                    return null;
                }
                // decode the stream into a bitmap
                return BitmapFactory.decodeStream(assetInputStream);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                // Set image
                updateUI(highTemp,lowTemp,bitmap);
            }
        }


        @Override

        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            mCenterX = width / 2f;
            mCenterY = width / 2f;

            float largeTextSize = height / 6;
            float mediumTextSize = height / 8;
            float smallTextSize = height / 12;

            mTimePaint.setTextSize(largeTextSize);

            mDatePaint.setTextSize(smallTextSize);
            mHighPaint.setTextSize(mediumTextSize);
            mLowPaint.setTextSize(mediumTextSize);

            if (!mLowBitAmbient) {
                if (mWeatherArt == null) {
                    mWeatherArt = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);
                }
                mWeatherArtMonochrome = toMonochrome(mWeatherArt);
            }
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }


        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // Draw hours and minutes
            // Spannable string help from http://stackoverflow.com/a/10410843/6591585
            String hours = new SimpleDateFormat("h:", Locale.getDefault()).format(mCalendar.getTime());
            String minutes = new SimpleDateFormat("mm", Locale.getDefault()).format(mCalendar.getTime());
            Spannable time = new SpannableString(hours + minutes);
            StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
            time.setSpan(boldSpan, 0, hours.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            StaticLayout timeLayout = new StaticLayout(time, mTimePaint, getTextWidth(hours + minutes + ":", mTimePaint), Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
            canvas.save();
            canvas.translate(mCenterX - timeLayout.getWidth() / 2, mCenterY - 2*mTimePaint.getTextSize());
            timeLayout.draw(canvas);
            canvas.restore();

            // Draw the date
            SimpleDateFormat dateFormat = new SimpleDateFormat("E, MMM d yyyy", Locale.getDefault());
            String date = dateFormat.format(mCalendar.getTime());
            canvas.drawText(date.toUpperCase(), mCenterX, mCenterY - mDatePaint.getTextSize() / 2, mDatePaint);

            // Draw the high temperature
            String suffix = "\u00B0";
            if (mHighTemp == null) {
                mHighTemp = "72" + suffix;
            }
            canvas.drawText(mHighTemp, mCenterX, mCenterY + mHighPaint.getTextSize() * 3 / 2, mHighPaint);

            // Draw the low temperature
            if (mLowTemp == null) {
                mLowTemp = "69" + suffix;
            }
            canvas.drawText(mLowTemp, mCenterX + getTextWidth(" 00* ",mHighPaint) / 2, mCenterY + mLowPaint.getTextSize() * 3 / 2, mLowPaint);

            // Draw the weather art
            if (mWeatherArt == null) {
                mWeatherArt = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);
            }

            canvas.save();
            canvas.translate(mCenterX / 2 - 10, mCenterY + mLowPaint.getTextSize() * 1 / 2);

            if (isInAmbientMode()) {
                canvas.drawBitmap(mWeatherArtMonochrome,
                        null,
                        new Rect(0, 0, canvas.getWidth() / 6, canvas.getWidth() / 6),
                        null);
            } else {
                canvas.drawBitmap(mWeatherArt,
                        null,
                        new Rect(0, 0, canvas.getWidth() / 6, canvas.getWidth() / 6),
                        null);
            }
            canvas.restore();

            /* Draw rectangle behind peek card in ambient mode to improve readability. */
            if (mAmbient) {
                canvas.drawRect(mPeekCardBounds, mBackgroundPaint);
            }
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            mPeekCardBounds.set(rect);
        }

        /**
         * From http://stackoverflow.com/a/38635239/6591585
         * @param bitmap
         */
        private Bitmap toMonochrome(Bitmap bitmap)
        {
            Bitmap bwBitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.RGB_565 );
            float[] hsv = new float[ 3 ];
            for( int col = 0; col < bitmap.getWidth(); col++ ) {
                for( int row = 0; row < bitmap.getHeight(); row++ ) {
                    Color.colorToHSV( bitmap.getPixel( col, row ), hsv );
                    if( hsv[ 2 ] > 0.5f ) {
                        bwBitmap.setPixel( col, row, 0xffffffff );
                    } else {
                        bwBitmap.setPixel( col, row, 0xff000000 );
                    }
                }
            }
            return bwBitmap;
        }

        /**
         * From http://stackoverflow.com/a/15398662/6591585
         * @param text
         * @param paint
         * @return
         */
        public int getTextWidth(String text, Paint paint) {
            Rect bounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), bounds);
            int width = bounds.left + bounds.width();
            return width;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}

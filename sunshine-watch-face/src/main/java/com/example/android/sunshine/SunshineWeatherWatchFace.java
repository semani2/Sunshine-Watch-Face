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

package com.example.android.sunshine;

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
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWeatherWatchFace extends CanvasWatchFaceService {
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
        private final WeakReference<SunshineWeatherWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWeatherWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWeatherWatchFace.Engine engine = mWeakReference.get();
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
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {
        private static final String EMPTY_STRING = "";

        private static final String WEATHER_DATA_PATH = "/sunshine/weather";

        private static final String MIN_TEMP_KEY = "sai.development.sunshineweatherface.min_temp_key";
        private static final String MAX_TEMP_KEY = "sai.development.sunshineweatherface.max_temp_key";
        private static final String WEATHER_ICON_KEY = "sai.development.sunshineweatherface.weather_icon_key";


        private final String TAG = SunshineWeatherWatchFace.class.getSimpleName();

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mClockTextPaint;
        Paint mDateTextPaint;

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

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private GoogleApiClient mGoogleApiClient;

        private String minTemp = EMPTY_STRING;
        private String maxTemp = EMPTY_STRING;
        private Bitmap weatherBitmapAsset = null;

        private Date currentDate;
        private SimpleDateFormat simpleDateFormat;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            final Resources resources = SunshineWeatherWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.colorSunshine));

            mClockTextPaint = new Paint();
            mClockTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDateTextPaint = new Paint();
            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();
            mCalendar.setTimeZone(TimeZone.getDefault());

            simpleDateFormat = new SimpleDateFormat("ccc, MMM d yyyy");
            simpleDateFormat.setCalendar(mCalendar);

            currentDate = new Date();

            mGoogleApiClient = new GoogleApiClient.Builder(getApplicationContext()).
                    addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            mGoogleApiClient.connect();
        }

        /* Google API Client callbacks */

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "Connected to Google Api Client");

            //Add data listener
            Wearable.DataApi.addListener(mGoogleApiClient, this);

            //Send Sync weather message to device
            new SyncWeatherTask(mGoogleApiClient).execute();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Connection to Google Api client suspended");

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "Connection to Google Api client failed " + connectionResult);
        }


        /**
         * Data Api Listener callback
         * @param dataEventBuffer
         */
        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "Data Listener OnDataChanged");
            for(DataEvent dataEvent: dataEventBuffer) {
                if(dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();

                    // Check if path of data item matches weather data path
                    if(dataItem.getUri().getPath().equalsIgnoreCase(WEATHER_DATA_PATH)) {
                        // Extract out weather information
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();

                        minTemp = dataMap.getString(MIN_TEMP_KEY);
                        maxTemp = dataMap.getString(MAX_TEMP_KEY);
                        //weatherBitmapAsset = loadBitmapFromAsset(dataMap.getAsset(WEATHER_ICON_KEY));
                    }
                }
            }
        }

        /**
         * Source : https://developer.android.com/training/wearables/data-layer/assets.html
         * @param asset
         * @return
         */
        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(5000, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();
            mGoogleApiClient.disconnect();

            if (assetInputStream == null) {
                Log.w(TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }


        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mGoogleApiClient.disconnect();
            super.onDestroy();
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
            SunshineWeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWeatherWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            float clockTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_date_size_round : R.dimen.digital_text_date_size);

            mClockTextPaint.setTextSize(clockTextSize);
            mDateTextPaint.setTextSize(dateTextSize);
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
                    mClockTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            Log.d(TAG, "Google Api Client Connection Status: " + mGoogleApiClient.isConnected());
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            int xCoordinateCenter = bounds.centerX();
            int yCoordinateCenter = bounds.centerY();

            int xCoordinateClock = xCoordinateCenter - (int) convertDpToPixel(45);
            int yCoordinateClock = yCoordinateCenter - (int) convertDpToPixel(40);

            int xCoordinateDate = xCoordinateCenter - (int) convertDpToPixel(50);
            int yCoordinateDate = yCoordinateCenter - (int) convertDpToPixel(20);

            int xCoordinateWeather = xCoordinateCenter;
            int yCoordinateWeather = yCoordinateCenter + (int) convertDpToPixel(20);

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                    mCalendar.get(Calendar.MINUTE));

            String date = simpleDateFormat.format(currentDate);

            //Draw clock
            canvas.drawText(text, xCoordinateClock, yCoordinateClock, mClockTextPaint);

            //Draw date
            canvas.drawText(date, xCoordinateDate, yCoordinateDate, mDateTextPaint);

            if(!minTemp.equalsIgnoreCase("")) {
                String weather = String.format(Locale.ENGLISH, "%s  %s", maxTemp, minTemp);
                canvas.drawText(weather, xCoordinateWeather, yCoordinateWeather, mDateTextPaint);
            }

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

    private float convertDpToPixel(float dp) {
        Resources r = getResources();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, r.getDisplayMetrics());
    }

    private class SyncWeatherTask extends AsyncTask<Void, Void, Void> {

        private final String TAG = SyncWeatherTask.class.getSimpleName();
        private final GoogleApiClient mGoogleApiClient;

        private static final String SYNC_WEATHER_PATH = "/sync-weather";

        public SyncWeatherTask(GoogleApiClient googleApiClient) {
            this.mGoogleApiClient = googleApiClient;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            String nodeId = getNodeId();
            if(nodeId != null) {
                sendMessageToDevice(nodeId);
            }
            return null;
        }

        private String getNodeId() {
            NodeApi.GetConnectedNodesResult nodes =
                    Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

            if(nodes != null && nodes.getNodes().size() > 0) {
                return nodes.getNodes().get(0).getId();
            }
            return null;
        }

        private void sendMessageToDevice(String nodeId) {
            Wearable.MessageApi.sendMessage(mGoogleApiClient,
                    nodeId,
                    SYNC_WEATHER_PATH,
                    new byte[0])
                    .setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                        @Override
                        public void onResult(@NonNull MessageApi.SendMessageResult sendMessageResult) {
                            if (!sendMessageResult.getStatus().isSuccess()) {
                                Log.e(TAG, "Failed to send message with status code: "
                                        + sendMessageResult.getStatus().getStatusCode());
                            }
                            else {
                                Log.d(TAG, "Message to device sent successfully");
                            }
                        }
                    });
        }
    }

}

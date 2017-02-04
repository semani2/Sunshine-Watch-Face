package com.example.android.sunshine.utilities;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * Created by sai on 2/2/17.
 */

public class SunshineWatchFaceUtils {

    private static final String TAG = SunshineWatchFaceUtils.class.getSimpleName();
    /*
     * The columns of data that we are interested in displaying within our notification to let
     * the user know there is new weather data available.
     */
    public static final String[] WEATHER_NOTIFICATION_PROJECTION = {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
    };

    /*
     * We store the indices of the values in the array of Strings above to more quickly be able
     * to access the data from our query. If the order of the Strings above changes, these
     * indices must be adjusted to match the order of the Strings.
     */
    public static final int INDEX_WEATHER_ID = 0;
    public static final int INDEX_MAX_TEMP = 1;
    public static final int INDEX_MIN_TEMP = 2;

    private static final String WEATHER_DATA_PATH = "/sunshine/weather";

    private static final String MIN_TEMP_KEY = "sai.development.sunshineweatherface.min_temp_key";
    private static final String MAX_TEMP_KEY = "sai.development.sunshineweatherface.max_temp_key";
    private static final String WEATHER_ICON_KEY = "sai.development.sunshineweatherface.weather_icon_key";
    private static final String WEATHER_COND_KEY = "sai.development.sunshineweaterface.weather_cond_key";
    private static final String WEATHER_ID_KEY = "sai.development.sunshineweaterface.weather_id_key";


    public static void updateWearInfo(Context context) {
         /* Build the URI for today's weather in order to show up to date data in notification */
        Uri todaysWeatherUri = WeatherContract.WeatherEntry
                .buildWeatherUriWithDate(SunshineDateUtils.normalizeDate(System.currentTimeMillis()));

        /*
         * The MAIN_FORECAST_PROJECTION array passed in as the second parameter is defined in our WeatherContract
         * class and is used to limit the columns returned in our cursor.
         */
        Cursor todayWeatherCursor = context.getContentResolver().query(
                todaysWeatherUri,
                WEATHER_NOTIFICATION_PROJECTION,
                null,
                null,
                null);

        /*
         * If todayWeatherCursor is empty, moveToFirst will return false. If our cursor is not
         * empty, we want to show the notification.
         */
        if (todayWeatherCursor.moveToFirst()) {

            /* Weather ID as returned by API, used to identify the icon to be used */
            int weatherId = todayWeatherCursor.getInt(INDEX_WEATHER_ID);
            double high = todayWeatherCursor.getDouble(INDEX_MAX_TEMP);
            double low = todayWeatherCursor.getDouble(INDEX_MIN_TEMP);
            String weatherCondition = SunshineWeatherUtils
                    .getStringForWeatherCondition(context, weatherId);

            String maxTemp = SunshineWeatherUtils.formatTemperature(context, high);

            String minTemp = SunshineWeatherUtils.formatTemperature(context, low);

            final GoogleApiClient googleApiClient = new GoogleApiClient.Builder(context)
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(@Nullable Bundle bundle) {

                        }

                        @Override
                        public void onConnectionSuspended(int i) {

                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

                        }
                    })
                    .addApi(Wearable.API)
                    .build();

            googleApiClient.connect();

            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_DATA_PATH);
            putDataMapRequest.getDataMap().putString(MIN_TEMP_KEY, minTemp);
            putDataMapRequest.getDataMap().putString(MAX_TEMP_KEY, maxTemp);
            putDataMapRequest.getDataMap().putLong("TIME", System.currentTimeMillis());
            putDataMapRequest.getDataMap().putString(WEATHER_COND_KEY, weatherCondition);
            putDataMapRequest.getDataMap().putInt(WEATHER_ID_KEY, weatherId);

            PutDataRequest putDataRequest = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(googleApiClient, putDataRequest)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.e(TAG, "Failed to send weather data to wear");
                            }
                            googleApiClient.disconnect();
                        }
                    });

        }

        /* Always close your cursor when you're done with it to avoid wasting resources. */
        todayWeatherCursor.close();

    }
}

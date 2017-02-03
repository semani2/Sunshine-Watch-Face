package com.example.android.sunshine.services;

import android.util.Log;

import com.example.android.sunshine.sync.SunshineSyncTask;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Created by sai on 2/3/17.
 */

public class SunshineWearableListenerService extends WearableListenerService {

    private static final String TAG = SunshineWearableListenerService.class.getSimpleName();

    private static final String SYNC_WEATHER_PATH = "/sync-weather";

    private GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.d(TAG, "Message received from the wearable");

        if(messageEvent.getPath().equalsIgnoreCase(SYNC_WEATHER_PATH)) {
            SunshineSyncTask.syncWeather(this);
        }
    }
}

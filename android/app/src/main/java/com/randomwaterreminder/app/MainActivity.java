package com.randomwaterreminder.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WaterReminderPlugin.class);
        super.onCreate(savedInstanceState);
        ScreenStateTracker.INSTANCE.start(this);
    }

    @Override
    public void onStart() {
        super.onStart();
        AppVisibilityTracker.INSTANCE.setForeground(true);
    }

    @Override
    public void onStop() {
        AppVisibilityTracker.INSTANCE.setForeground(false);
        super.onStop();
    }
}

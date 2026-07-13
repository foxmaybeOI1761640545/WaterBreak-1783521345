package com.randomwaterreminder.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WaterReminderPlugin.class);
        registerPlugin(AppUpdatePlugin.class);
        dismissScreenAlertForNavigation(getIntent());
        super.onCreate(savedInstanceState);
        ScreenStateTracker.INSTANCE.start(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        dismissScreenAlertForNavigation(intent);
        super.onNewIntent(intent);
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

    private void dismissScreenAlertForNavigation(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "open".equals(data.getAuthority()) && "/screen".equals(data.getPath())) {
            ReminderAlertActivity.Companion.dismissActive(ReminderType.SCREEN_LIMIT, "");
        }
    }
}

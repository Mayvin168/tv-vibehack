package com.gathin.tvhome;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;

/**
 * Invisible activity whose only job is to turn the screen on when started — the
 * alarm-clock trick. Lets the phone remote wake the TV when it is merely asleep
 * (process still alive). A TV in deep standby kills the process, and then no app
 * can wake it; that needs the physical remote.
 */
public class WakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Handler().postDelayed(this::finish, 400);
    }
}

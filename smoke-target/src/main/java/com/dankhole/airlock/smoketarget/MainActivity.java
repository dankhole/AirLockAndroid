package com.dankhole.airlock.smoketarget;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView label = new TextView(this);
        label.setGravity(Gravity.CENTER);
        label.setText(R.string.smoke_target_label);
        label.setTextSize(24);
        setContentView(label);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if (getIntent().getBooleanExtra("open_second", false)) {
            getIntent().removeExtra("open_second");
            // Posting after resume produces A paused -> B resumed -> A stopped.
            getWindow().getDecorView().post(() ->
                    startActivity(new Intent(this, SecondActivity.class)));
        }
    }
}

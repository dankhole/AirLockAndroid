package com.dankhole.airlock.smoketarget;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Same-package lifecycle regression destination for the standalone smoke target. */
public final class SecondActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView label = new TextView(this);
        label.setGravity(Gravity.CENTER);
        label.setText(R.string.smoke_target_second_label);
        label.setTextSize(24);
        setContentView(label);
    }
}

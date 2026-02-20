package net.devemperor.asr;

import android.app.Application;

public class DictateApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DictateUtils.applyApplicationLocale(this);
    }
}

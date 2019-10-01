package com.psiphon3;

import android.app.Application;
import android.content.Context;

import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;

public class PsiphonApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        // Do not set locale in the base context if we detected system language should be used
        // because it will prevent locale change when it is triggered via onConfigurationChanged
        // callback when user changes locale in the OS settings.
        LocaleManager localeManager = LocaleManager.getInstance(base);
        if (localeManager.isSetToSystemLocale()) {
            super.attachBaseContext(base);
        } else {
            super.attachBaseContext(localeManager.setLocale(base));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // If an Rx subscription is disposed while the observable is still running its async task
        // which may throw an error the error will have nowhere to go and will result in an uncaught
        // UndeliverableException being thrown. We are going to set up a global error handler to make
        // sure the app is not crashed in this case. For more details see
        // https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
        RxJavaPlugins.setErrorHandler(e -> {
            if (e instanceof UndeliverableException) {
                e = e.getCause();
            }
            Utils.MyLog.g(String.format("RxJava undeliverable exception received: %s", e.getMessage()));
        });
    }
}

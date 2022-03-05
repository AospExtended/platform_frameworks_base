package org.aospextended.android.systemui;

import android.content.Context;
import android.content.res.AssetManager;

import com.google.android.systemui.gesture.BackGestureTfClassifierProviderGoogle;

import org.aospextended.android.systemui.dagger.DaggerGlobalRootComponentAEX;
import org.aospextended.android.systemui.dagger.GlobalRootComponentAEX;
import org.aospextended.android.systemui.dagger.SysUIComponentAEX;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.navigationbar.gestural.BackGestureTfClassifierProvider;
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

public class SystemUIAEXFactory extends SystemUIFactory {
    @Override
    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerGlobalRootComponentAEX.builder()
                .context(context)
                .build();
    }

    @Override
    public BackGestureTfClassifierProvider createBackGestureTfClassifierProvider(AssetManager am, String modelName) {
        return new BackGestureTfClassifierProviderGoogle(am, modelName);
    }

    @Override
    public void init(Context context, boolean fromTest) throws ExecutionException, InterruptedException {
        super.init(context, fromTest);
        if (shouldInitializeComponents()) {
            ((SysUIComponentAEX) getSysUIComponent()).createKeyguardSmartspaceController();
        }
    }
}

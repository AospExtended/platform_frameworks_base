package org.aospextended.android.systemui;

import android.content.Context;

import org.aospextended.android.systemui.dagger.DaggerGlobalRootComponentAEX;
import org.aospextended.android.systemui.dagger.GlobalRootComponentAEX;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.dagger.GlobalRootComponent;

public class SystemUIAEXFactory extends SystemUIFactory {
    @Override
    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerGlobalRootComponentAEX.builder()
                .context(context)
                .build();
    }
}

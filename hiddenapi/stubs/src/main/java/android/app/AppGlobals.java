package android.app;

import android.content.pm.IPackageManager;

/**
 * Compile-time stub for the hidden {@code android.app.AppGlobals}: the only
 * handle the module needs is the {@code package} binder this already caches,
 * which inside system_server is the local PackageManagerService object.
 */
public class AppGlobals {
    public static IPackageManager getPackageManager() {
        throw new UnsupportedOperationException("STUB");
    }
}

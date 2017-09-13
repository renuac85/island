package com.oasisfeng.island.provisioning;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;

import com.oasisfeng.island.analytics.Analytics;
import com.oasisfeng.island.engine.common.WellKnownPackages;
import com.oasisfeng.island.util.DevicePolicies;
import com.oasisfeng.island.util.Hacks;

import java.util.Set;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.N;

/**
 * Detect critical apps (including system and installed ones).
 *
 * Created by Oasis on 2017/9/12.
 */
public class CriticalAppsManager {

	static Set<String> detectCriticalPackages(final PackageManager pm, final DevicePolicies policies, final int flags) {
		final Set<String> pkgs = SystemAppsManager.detectCriticalSystemPackages(pm, policies, flags);

		if (SDK_INT >= N) pkgs.add(getCurrentWebViewPackageName());
		try { @SuppressLint("WrongConstant") // Chrome may not be current provider, since current provider may fallback to system WebView during provisioning.
			final ApplicationInfo chrome_info = pm.getApplicationInfo(WellKnownPackages.PACKAGE_GOOGLE_CHROME, Hacks.MATCH_ANY_USER_AND_UNINSTALLED);
			pkgs.add(chrome_info.packageName);
		} catch (final PackageManager.NameNotFoundException ignored) {}
		return pkgs;
	}

	@RequiresApi(N) public static @Nullable String getCurrentWebViewPackageName() {
		try {
			final IBinder service = Hacks.ServiceManager_getService.invoke("webviewupdate").statically();
			if (service == null) throw new RuntimeException("Service not found: webviewupdate");
			final Object webview_service = Hacks.IWebViewUpdateService$Stub_asInterface.invoke(service).statically();
			return Hacks.IWebViewUpdateService_getCurrentWebViewPackageName.invoke().on(webview_service);
		} catch (final Exception e) {
			Analytics.$().logAndReport(TAG, "Error detecting WebView provider.", e);
			return null;
		}
	}

	private static final String TAG = "CriticalApps";
}

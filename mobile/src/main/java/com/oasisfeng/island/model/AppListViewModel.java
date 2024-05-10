package com.oasisfeng.island.model;

import static android.widget.Toast.LENGTH_SHORT;
import static com.oasisfeng.island.analytics.Analytics.Param.ITEM_ID;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SearchView;
import android.widget.Toast;

import androidx.annotation.MenuRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import com.oasisfeng.android.app.Activities;
import com.oasisfeng.android.base.Scopes;
import com.oasisfeng.android.databinding.recyclerview.ItemBinder;
import com.oasisfeng.android.os.UserHandles;
import com.oasisfeng.android.ui.Dialogs;
import com.oasisfeng.androidx.lifecycle.NonNullMutableLiveData;
import com.oasisfeng.common.app.BaseAppListViewModel;
import com.oasisfeng.island.analytics.Analytics;
import com.oasisfeng.island.controller.IslandAppClones;
import com.oasisfeng.island.controller.IslandAppControl;
import com.oasisfeng.island.data.IslandAppInfo;
import com.oasisfeng.island.data.IslandAppListProvider;
import com.oasisfeng.island.featured.FeaturedListViewModel;
import com.oasisfeng.island.greenify.GreenifyClient;
import com.oasisfeng.island.mobile.BR;
import com.oasisfeng.island.mobile.BuildConfig;
import com.oasisfeng.island.mobile.R;
import com.oasisfeng.island.shortcut.IslandAppShortcut;
import com.oasisfeng.island.util.DevicePolicies;
import com.oasisfeng.island.util.Users;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * View model for apps
 *
 * Created by Oasis on 2015/7/7.
 */
@ParametersAreNonnullByDefault
public class AppListViewModel extends BaseAppListViewModel<AppViewModel> {

	private static final long QUERY_TEXT_DELAY = 300;	// The delay before typed query text is applied
	private static final String STATE_KEY_FILTER_HIDDEN_SYSTEM_APPS = "filter.hidden_sys";
	private static final String STATE_KEY_FILTER_CAN_QUERY_ALL_PACKAGES = "filter.can_query_all_packages";

	/** Workaround for menu res reference not supported by data binding */ public static @MenuRes int actions_menu = R.menu.app_actions;

	public UserHandle getCurrentProfile() { return mState.get(Intent.EXTRA_USER); }
	private void setCurrentProfile(final @Nullable UserHandle profile) { mState.set(Intent.EXTRA_USER, profile); }
	public LiveData<String> getFilterText() { return mState.getLiveData(SearchManager.QUERY); }
	private void setFilterText(final @Nullable String text) { mState.set(SearchManager.QUERY, text); }
	public MutableLiveData<Boolean> getFilterIncludeHiddenSystemApps() {
		return mState.getLiveData(STATE_KEY_FILTER_HIDDEN_SYSTEM_APPS, false);
	}
	public MutableLiveData<Boolean> getFilterCanQueryAllPackages() {
		return mState.getLiveData(STATE_KEY_FILTER_CAN_QUERY_ALL_PACKAGES, false);
	}

	public interface Filter extends Predicate<IslandAppInfo> {

		Filter Mainland = app -> Users.isParentProfile(app.user) && (app.isSystem() || app.isInstalled());	// Including uninstalled system app

		class IslandFilter implements Filter {

			@Override public boolean test(final IslandAppInfo app) {
				return mProfile.equals(app.user) && app.shouldShowAsEnabled() && (app.isInstalled() || app.isPlaceHolder());
			}

			IslandFilter(UserHandle profile) { mProfile = profile; }

			private final UserHandle mProfile;
		}
	}

	private Predicate<IslandAppInfo> activeFilters() {
		return mActiveFilters;
	}

	/** @see SearchView.OnQueryTextListener#onQueryTextChange(String) */
	public boolean onQueryTextChange(final String text) {
		mHandler.removeCallbacksAndMessages(SearchManager.QUERY);		// In case like "A -> AB -> A" in a short time
		if (! TextUtils.equals(text, getQueryText().getValue()))
			HandlerCompat.postDelayed(mHandler, () -> setFilterText(text), SearchManager.QUERY, QUERY_TEXT_DELAY);    // A short delay to avoid flickering during typing.
		return true;
	}

	private MutableLiveData<String> getQueryText() {
		return mState.getLiveData(SearchManager.QUERY, "");
	}

	/** @see SearchView.OnQueryTextListener#onQueryTextSubmit(String) */
	public void onQueryTextSubmit(final @Nullable String text) {
		mChipsVisible.setValue(! TextUtils.isEmpty(text) || getFilterIncludeHiddenSystemApps().getValue() == Boolean.TRUE);
		if (! TextUtils.equals(text, getQueryText().getValue())) {
			mHandler.removeCallbacksAndMessages(SearchManager.QUERY);
			setFilterText(text);
		}
	}

	public void onSearchClick(final SearchView view) {
		view.setQuery(getQueryText().getValue(), false);
	}

	/** @see SearchView.OnCloseListener#onClose() */
	public boolean onSearchViewClose() {
		onQueryTextSubmit(getQueryText().getValue());
		return true;
	}

	public void onQueryTextCleared() {
		onQueryTextSubmit("");
	}

	public void updateAppList(final String reason) {
		if (mFilterShared == null) return;		// When called by constructor   TODO: Obsolete?
		final UserHandle profile = getCurrentProfile();
		if (profile == null) return;
		Log.d(TAG, "Update app list in profile " + Users.toId(profile) + " for reason: " + reason);

		final Filter profile_filter = Users.isParentProfile(profile) ? Filter.Mainland : new Filter.IslandFilter(profile);
		Predicate<IslandAppInfo> filters = mFilterShared.and(profile_filter);
		if (getFilterIncludeHiddenSystemApps().getValue() != Boolean.TRUE)
			filters = filters.and(app -> ! app.isSystem() || app.isInstalled() && app.isLaunchable());  // Exclude system apps without launcher activity
		if (getFilterCanQueryAllPackages().getValue() == Boolean.TRUE)
			filters = filters.and(IslandAppInfo::canQueryAllPackages);
		final String text = getQueryText().getValue();
		if (text != null && text.length() != 0) {
			if (text.startsWith("package:")) {
				final String pkg = text.substring(8);
				filters = filters.and(app -> app.packageName.equals(pkg));
			} else {
				final String text_lc = text.toLowerCase();
				filters = filters.and(app -> app.packageName.toLowerCase().contains(text_lc) || app.getLabel().toLowerCase().contains(text_lc));
			}
		}
		mActiveFilters = filters;

		final AppViewModel selected = mSelection.getValue();
		clearSelection();

		IslandAppInfo.cacheLaunchableApps(requireNonNull(mAppListProvider.getContext()));	// Performance optimization
		final List<AppViewModel> apps = mAppListProvider.installedApps(profile).filter(filters).map(AppViewModel::new).collect(toList());
		IslandAppInfo.invalidateLaunchableAppsCache();
		replaceApps(apps);

		if (selected != null) for (final AppViewModel app : apps)
			if (app.info().packageName.equals(selected.info().packageName)) {
				setSelection(app);
				break;
			}
	}

	public AppListViewModel(final Application app, final SavedStateHandle savedState) {
		super(app, AppViewModel.class);
		mState = savedState;
		mAppListProvider = IslandAppListProvider.getInstance(app);
		mOwnerUserManaged = new DevicePolicies(app).isProfileOrDeviceOwnerOnCallingUser();
		mFilterShared = IslandAppListProvider.excludeSelf(app);

		final UserHandle user = savedState.get(Intent.EXTRA_USER);
		setCurrentProfile(user != null && (Users.isParentProfile(user) || Users.isProfileManagedByIsland(app, user)) ? user
				: Users.hasProfile() ? Users.profile : Users.getParentProfile());

		final String filter_text = getQueryText().getValue();
		if (! TextUtils.isEmpty(filter_text)) onQueryTextSubmit(filter_text);
		updateChipsVisibility();
	}

	public void onTabSwitched(final FragmentActivity activity, final TabLayout tabs, final TabLayout.Tab tab) {
		final int position = tab.getPosition();
		if (position == 0) {    // Discovery
			setCurrentProfile(null);
			mSelection.setValue(null);
			mFeatured.visible.setValue(Boolean.TRUE);
			mFeatured.update(activity);
			Analytics.log(TAG, "tab-discover");
			return;
		} else mFeatured.visible.setValue(Boolean.FALSE);

		if (position >= 2) {    // One of the Islands
			final Object tag = tab.getTag();
			if (tag instanceof UserHandle) {
				final UserHandle profile = (UserHandle) tag;
				if (Users.isProfileManagedByIsland(activity, profile)) {
					setCurrentProfile(profile);
					updateAppList("tab-island-" + Users.toId(profile));
					Analytics.log(TAG, "tab-island-" + UserHandles.getIdentifier(profile));
					return;
				} else tabs.removeTab(tab);     // In case it is removed elsewhere (e.g. by ADB shell)
			}
		}
		tabs.selectTab(tabs.getTabAt(1));   // Switch back to Mainland
		setCurrentProfile(Users.getParentProfile());
		updateChipsVisibility();
		updateAppList("tab-mainland");
		Analytics.log(TAG, "tab-mainland");
	}

	private void updateChipsVisibility() {
		mChipsVisible.setValue(getCurrentProfile() != null && (
				getFilterIncludeHiddenSystemApps().getValue() == Boolean.TRUE
				|| getFilterCanQueryAllPackages().getValue() == Boolean.TRUE
				|| ! TextUtils.isEmpty(getQueryText().getValue())));
	}

	public void updateActions(final Menu menu, final boolean cloneTipHidden) {
		mCloneTipHidden = cloneTipHidden;
		final AppViewModel selection = mSelection.getValue();
		if (selection == null) return;
		final IslandAppInfo app = selection.info();
		Analytics.$().trace("app", app.packageName).trace("user", Users.toId(app.user)).trace("hidden", app.isHidden())
				.trace("system", app.isSystem()).trace("critical", app.isCritical());
		final boolean exclusive = mAppListProvider.isExclusive(app);
		final boolean system = app.isSystem(), installed = app.isInstalled(), placeholder = app.isPlaceHolder(),
				in_owner = Users.isParentProfile(app.user),
				is_managed = in_owner ? mOwnerUserManaged : Users.isProfileManagedByIsland(getApplication(), app.user);
		final MenuItem clone = menu.findItem(R.id.menu_clone).setVisible(! placeholder && Users.hasProfile());
		if (! cloneTipHidden) {     // Move "Clone" before "App Settings" if the clone tip is not hidden yet.
			menu.removeItem(clone.getItemId());
			menu.add(clone.getGroupId(), clone.getItemId(), menu.findItem(R.id.menu_app_settings).getOrder() - 1, clone.getTitle());
		}
		menu.findItem(R.id.menu_freeze).setVisible(installed && is_managed && ! app.isHidden() && app.enabled);
		menu.findItem(R.id.menu_unfreeze).setVisible(installed && is_managed && app.isHidden());
		menu.findItem(R.id.menu_reinstall).setVisible(! installed && ! placeholder);
		menu.findItem(R.id.menu_app_settings).setVisible(! placeholder);
		menu.findItem(R.id.menu_remove).setVisible(installed && (exclusive ? system : (! system || app.shouldShowAsEnabled())));	// Disabled system app is treated as "removed".
		menu.findItem(R.id.menu_uninstall).setVisible(installed && exclusive && ! system);	// "Uninstall" for exclusive user app, "Remove" for exclusive system app.
		menu.findItem(R.id.menu_shortcut).setVisible(installed && is_managed && app.isLaunchable() && app.enabled);
		menu.findItem(R.id.menu_greenify).setVisible(installed && is_managed && app.enabled);

		if (BuildConfig.DEBUG) menu.findItem(R.id.menu_suspend).setVisible(! placeholder).setTitle(app.isSuspended() ? "Unsuspend" : "Suspend");
	}

	public void onPackagesUpdate(final Collection<IslandAppInfo> apps, final Menu menu) {
		final Predicate<IslandAppInfo> filters = activeFilters();
		for (final IslandAppInfo app : apps)
			if (filters.test(app)) {
				putApp(app.packageName, new AppViewModel(app));
			} else removeApp(app.packageName, app.user);
		updateActions(menu, mCloneTipHidden);
	}

	private void removeApp(final String pkg, final UserHandle user) {
		final AppViewModel app = getApp(pkg);
		if (app != null && app.info().user.equals(user)) super.removeApp(pkg);
	}

	public void onPackagesRemoved(final Collection<IslandAppInfo> apps, final Menu menu) {
		final Predicate<IslandAppInfo> filters = activeFilters();
		for (final IslandAppInfo app : apps)
			if (filters.test(app)) removeApp(app.packageName);
		updateActions(menu, mCloneTipHidden);
	}

	public void onItemLaunchIconClick(final Context context, final IslandAppInfo app) {
		IslandAppControl.launch(context, app);
	}

	public boolean onActionClick(final Context context, final MenuItem item) {
		final AppViewModel selection = mSelection.getValue();
		if (selection == null) return false;
		final IslandAppInfo app = selection.info();
		final String pkg = app.packageName;

		final int id = item.getItemId();
		if (id == R.id.menu_clone) {
			new IslandAppClones((FragmentActivity) context, this, app).request();
		} else if (id == R.id.menu_freeze) {// Select the next alive app, or clear selection.
			Analytics.$().event("action_freeze").with(ITEM_ID, pkg).send();

			final Activity activity = Activities.findActivityFrom(context);
			if (activity != null && IslandAppListProvider.getInstance(context).isCritical(pkg)) {
				Dialogs.buildAlert(activity, R.string.dialog_title_warning, R.string.dialog_critical_app_warning)
						.withCancelButton().withOkButton(() -> freezeApp(context, selection)).show();
			} else freezeApp(context, selection);
		} else if (id == R.id.menu_unfreeze) {
			Analytics.$().event("action_unfreeze").with(ITEM_ID, pkg).send();
			final Boolean unfrozen = IslandAppControl.unfreeze(app);
			if (unfrozen == null) {
				Toast.makeText(context, R.string.prompt_island_not_ready, Toast.LENGTH_LONG).show();
			} else if (unfrozen == Boolean.TRUE) {
				refreshAppStateAsSysBugWorkaround(context, app);
				clearSelection();
			}
		} else if (id == R.id.menu_app_settings) {
			IslandAppControl.launchExternalAppSettings(this, app);
		} else if (id == R.id.menu_remove || id == R.id.menu_uninstall) {
			IslandAppControl.requestRemoval(requireNonNull(Activities.findActivityFrom(context)), selection.info());
		} else if (id == R.id.menu_reinstall) {
			onReinstallRequested(context);
		} else if (id == R.id.menu_shortcut) {
			onShortcutRequested(context);
		} else if (id == R.id.menu_greenify) {
			onGreenifyRequested(context);
		} else if (id == R.id.menu_suspend) {
			final boolean done = IslandAppControl.setSuspended(app, ! app.isSuspended());
			Toast.makeText(context, done ? "Done" : "Failed", LENGTH_SHORT).show();
		}
		return true;
	}

	private void freezeApp(final Context context, final AppViewModel app_vm) {
		final IslandAppInfo app = app_vm.info();
		if (IslandAppControl.freeze(app)) {
			// Select the next app for convenient continuous freezing.
			final int next_index = indexOf(app_vm) + 1;
			final AppViewModel next;
			if (next_index < size() && (next = getAppAt(next_index)).state == AppViewModel.State.Alive) setSelection(next);
			else clearSelection();
		}
		refreshAppStateAsSysBugWorkaround(context, app);
	}

	private void onShortcutRequested(final Context context) {
		final AppViewModel app_vm = mSelection.getValue();
		if (app_vm == null) return;
		final ApplicationInfo app= app_vm.info();
		final String pkg = app.packageName;
		Analytics.$().event("action_create_shortcut").with(ITEM_ID, pkg).send();
		IslandAppShortcut.requestPin(context, app);
	}

	private void onGreenifyRequested(final Context context) {
		if (mSelection.getValue() == null) return;
		final IslandAppInfo app = mSelection.getValue().info();
		Analytics.$().event("action_greenify").with(ITEM_ID, app.packageName).send();

		final String mark = "greenify-explained";
		final Boolean greenify_ready = GreenifyClient.checkGreenifyVersion(context);
		final boolean greenify_installed = greenify_ready != null;
		final boolean unavailable_or_version_too_low = greenify_ready == null || ! greenify_ready;
		if (unavailable_or_version_too_low || ! Scopes.app(context).isMarked(mark)) {
			String message = context.getString(R.string.dialog_greenify_explanation);
			if (greenify_installed && unavailable_or_version_too_low)
				message += "\n\n" + context.getString(R.string.dialog_greenify_version_too_low);
			final int button = ! greenify_installed ? R.string.action_install : ! greenify_ready ? R.string.action_upgrade : R.string.action_continue;
			new AlertDialog.Builder(context).setTitle(R.string.dialog_greenify_title).setMessage(message).setPositiveButton(button, (d, w) -> {
				if (! unavailable_or_version_too_low) {
					Scopes.app(context).markOnly(mark);
					greenify(context, app);
				} else GreenifyClient.openInAppMarket(context);
			}).show();
		} else greenify(context, app);
	}

	private static void greenify(final Context context, final IslandAppInfo app) {
		if (! GreenifyClient.greenify(context, app.packageName, app.user))
			Toast.makeText(context, R.string.toast_greenify_failed, Toast.LENGTH_LONG).show();
	}

	private void onReinstallRequested(final Context context) {
		if (mSelection.getValue() == null) return;
		final IslandAppInfo app = mSelection.getValue().info();
		final Activity activity = Activities.findActivityFrom(context);
		if (activity == null) reinstallSystemApp(context, app);
		else Dialogs.buildAlert(activity, R.string.dialog_title_warning, R.string.dialog_reinstall_system_app_warning)
				.withCancelButton().setPositiveButton(R.string.action_continue, (d, w) -> reinstallSystemApp(context, app)).show();
	}

	private static void reinstallSystemApp(final Context context, final IslandAppInfo app) {
		new DevicePolicies(context).enableSystemApp(app.packageName);
	}

	/** Possible 10s delay before the change broadcast could be received (due to Android issue 225880), so we force a refresh immediately. */
	private static void refreshAppStateAsSysBugWorkaround(final Context context, final IslandAppInfo app) {
		IslandAppListProvider.getInstance(context).refreshPackage(app.packageName, app.user, false);
	}

	public final void onItemClick(final AppViewModel clicked) {
		setSelection(clicked != mSelection.getValue() ? clicked : null);	// Click the selected one to deselect
	}

	@SuppressWarnings("MethodMayBeStatic") public final void onBottomSheetClick(final View view) {
		final BottomSheetBehavior<View> bottom_sheet = BottomSheetBehavior.from(view);
		bottom_sheet.setState(BottomSheetBehavior.STATE_EXPANDED);
	}

	/* Parcelable */

	public final BottomSheetBehavior.BottomSheetCallback bottom_sheet_callback = new BottomSheetBehavior.BottomSheetCallback() {

		@Override public void onStateChanged(@NonNull final View bottom_sheet, final int new_state) {
			if (new_state == BottomSheetBehavior.STATE_HIDDEN) clearSelection();
			else bottom_sheet.bringToFront();	// Force a lift due to bottom sheet appearing underneath BottomNavigationView on some devices, despite the layout order or elevation.
		}

		@Override public void onSlide(@NonNull final View bottomSheet, final float slideOffset) {}
	};

	public final ItemBinder<AppViewModel> item_binder = (container, model, item) -> {
		item.setVariable(BR.app, model);
		item.setVariable(BR.apps, this);
	};

	public FeaturedListViewModel mFeatured;     // TODO: Move to fragment
	private final SavedStateHandle mState;
	/* Attachable fields */
	private final IslandAppListProvider mAppListProvider;
	/* Transient fields */
	public final NonNullMutableLiveData<Boolean> mChipsVisible = new NonNullMutableLiveData<>(false);
	private final Predicate<IslandAppInfo> mFilterShared;			// All other filters to apply always
	private final boolean mOwnerUserManaged;
	private Predicate<IslandAppInfo> mActiveFilters;		// The active composite filters
	private final Handler mHandler = new Handler(Looper.getMainLooper());
	private boolean mCloneTipHidden;

	private static final String TAG = "Island.ALVM";
}

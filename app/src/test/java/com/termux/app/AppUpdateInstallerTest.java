package com.termux.app;

import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.content.FileProvider;

import com.termux.workflow.AppRelease;
import com.termux.workflow.AppUpdateActivity;
import com.termux.workflow.AppUpdateInstaller;
import com.termux.workflow.AppUpdateState;
import com.termux.workflow.AppUpdateViewModel;
import com.termux.workflow.UpdatePhase;

import kotlinx.coroutines.flow.MutableStateFlow;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class AppUpdateInstallerTest {
    private Context context;
    private File apk;
    private AppRelease release;
    private PackageInfo archive;
    private ShadowPackageManager packages;

    @Before
    public void setUp() throws Exception {
        // Robolectric assigns a new cache directory for each test.
        ((Map<?, ?>) ReflectionHelpers.getStaticField(FileProvider.class, "sCache")).clear();
        context = RuntimeEnvironment.getApplication();
        packages = shadowOf(context.getPackageManager());
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(),
            PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA);
        installed.versionCode = 119;
        installed.signatures = new Signature[]{new Signature("1234")};
        packages.installPackage(installed);
        archive = new PackageInfo();
        archive.packageName = context.getPackageName();
        archive.versionCode = 120;
        archive.signatures = installed.signatures;
        apk = new File(context.getCacheDir(), "updates/update.apk");
        assertTrue(apk.getParentFile().isDirectory() || apk.getParentFile().mkdirs());
        apk.createNewFile();
        packages.setPackageArchiveInfo(apk.getPath(), archive);
        release = new AppRelease(context.getPackageName(), 120, "0.118.2", true, null, 100, "hash");
    }

    @Test
    public void validatesIdentityVersionAndSignatureBeforeInstallation() {
        AppUpdateInstaller.INSTANCE.validate(context, apk, release);
        archive.packageName = "another.app";
        assertThrows(IllegalArgumentException.class, () -> AppUpdateInstaller.INSTANCE.validate(context, apk, release));
        archive.packageName = context.getPackageName();
        archive.versionCode = 119;
        assertThrows(IllegalArgumentException.class, () -> AppUpdateInstaller.INSTANCE.validate(context, apk, release));
        archive.versionCode = 120;
        archive.signatures = new Signature[]{new Signature("5678")};
        assertThrows(IllegalArgumentException.class, () -> AppUpdateInstaller.INSTANCE.validate(context, apk, release));
    }

    @Test
    public void installerUsesPrivateContentUriWithReadPermission() {
        Intent intent = AppUpdateInstaller.INSTANCE.intent(context, apk);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("content", intent.getData().getScheme());
        assertEquals(context.getPackageName() + ".updates", intent.getData().getAuthority());
        assertEquals("application/vnd.android.package-archive", intent.getType());
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void completedDownloadAutomaticallyOpensInstallerOnlyOnce() {
        packages.setCanRequestPackageInstalls(true);
        try (ActivityController<AppUpdateActivity> controller = Robolectric.buildActivity(AppUpdateActivity.class).setup()) {
            AppUpdateActivity activity = controller.get();
            setReady(activity);
            shadowOf(Looper.getMainLooper()).idle();
            Intent installer = shadowOf(activity).getNextStartedActivity();
            AppUpdateViewModel model = ReflectionHelpers.callInstanceMethod(activity, "getModel");
            assertNotNull(model.getState().getValue().getMessage(), installer);
            assertEquals(Intent.ACTION_VIEW, installer.getAction());
            assertNull(shadowOf(activity).getNextStartedActivity());
            controller.pause().resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(activity).getNextStartedActivity());
        }
    }

    @Test
    public void missingInstallPermissionOpensSystemPermissionPage() {
        packages.setCanRequestPackageInstalls(false);
        try (ActivityController<AppUpdateActivity> controller = Robolectric.buildActivity(AppUpdateActivity.class).setup()) {
            setReady(controller.get());
            shadowOf(Looper.getMainLooper()).idle();
            Intent intent = shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.getAction());
            assertEquals("package:" + context.getPackageName(), intent.getDataString());
            shadowOf(controller.get()).receiveResult(intent, Activity.RESULT_CANCELED, null);
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
        }
    }

    @Test
    public void grantingInstallPermissionContinuesToInstaller() {
        packages.setCanRequestPackageInstalls(false);
        try (ActivityController<AppUpdateActivity> controller = Robolectric.buildActivity(AppUpdateActivity.class).setup()) {
            setReady(controller.get());
            shadowOf(Looper.getMainLooper()).idle();
            Intent permission = shadowOf(controller.get()).getNextStartedActivity();
            packages.setCanRequestPackageInstalls(true);
            shadowOf(controller.get()).receiveResult(permission, Activity.RESULT_OK, null);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Intent.ACTION_VIEW, shadowOf(controller.get()).getNextStartedActivity().getAction());
        }
    }

    @Test
    public void backgroundCompletionWaitsUntilActivityResumes() {
        packages.setCanRequestPackageInstalls(true);
        try (ActivityController<AppUpdateActivity> controller = Robolectric.buildActivity(AppUpdateActivity.class).setup()) {
            controller.pause();
            setReady(controller.get());
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(controller.get()).getNextStartedActivity());
            controller.resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Intent.ACTION_VIEW, shadowOf(controller.get()).getNextStartedActivity().getAction());
        }
    }

    private void setReady(AppUpdateActivity activity) {
        AppUpdateViewModel model = ReflectionHelpers.callInstanceMethod(activity, "getModel");
        MutableStateFlow<AppUpdateState> state = ReflectionHelpers.getField(model, "mutableState");
        state.setValue(new AppUpdateState("0.118.1 (119)", "Test host", UpdatePhase.Ready, release, 100, apk, true, "", null));
    }
}

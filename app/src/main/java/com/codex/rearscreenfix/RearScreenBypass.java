package com.codex.rearscreenfix;

import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class RearScreenBypass implements IXposedHookLoadPackage {
    private static final String TAG = "RearScreenBypass";

    private static final String PKG_THEME_MANAGER = "com.android.thememanager";
    private static final String PKG_SUBSCREEN = "com.xiaomi.subscreencenter";

    private static final boolean LOG_VERBOSE = true;
    private static final boolean REWRITE_THEME_MANAGER_WIDGET_PATHS = true;
    private static final boolean FORCE_ACCEPT_WIDGETS_IN_SUBSCREEN = true;
    private static final boolean STAGE_RIGHT_FILE_BEFORE_APPLY = true;
    private static final boolean STAGE_MTZ_FILE_BEFORE_APPLY = true;
    private static final boolean ALLOW_DONOR_RIGHT_FILE_FALLBACK = true;
    private static final boolean BYPASS_MISSING_RIGHT_FILE_COPY = true;
    private static final boolean PREFILL_THEME_MANAGER_SNAPSHOT_PATHS = true;
    private static final boolean REDIRECT_WHITE_RUNTIME_PATHS_FOR_LOOSE_AI = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (PKG_THEME_MANAGER.equals(lpparam.packageName)) {
            hookThemeManager(lpparam.classLoader);
        } else if (PKG_SUBSCREEN.equals(lpparam.packageName)) {
            hookSubScreenCenter(lpparam.classLoader);
        }
    }

    private void hookThemeManager(ClassLoader classLoader) {
        log("Hooking ThemeManager");

        hookSafe(() -> XposedHelpers.findAndHookMethod(
                "com.rearScreen.manager.RearScreenCenterManager",
                classLoader,
                "p",
                "com.rearScreen.bean.RearScreenListItemBean",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object bean = param.args[0];
                        Object widget = param.getResult();
                        if (bean == null || widget == null) {
                            return;
                        }

                        String currentPath = safeCallString(widget, "getPath");
                        String currentConfigPath = safeCallString(widget, "getConfigPath");
                        Bundle extra = safeCallBundle(widget, "getExtra");

                        List<String> candidates = new ArrayList<>();
                        addIfNonEmpty(candidates, currentPath);
                        addIfNonEmpty(candidates, bundleGet(extra, "mtzSnapshotPath"));
                        addIfNonEmpty(candidates, bundleGet(extra, "snapshotPath"));
                        addIfNonEmpty(candidates, safeCallString(bean, "getValidMtzPath"));
                        addIfNonEmpty(candidates, safeCallString(bean, "getResLocalPath"));
                        addIfNonEmpty(candidates, safeCallString(bean, "getResSnapshotPath"));
                        addIfNonEmpty(candidates, safeCallString(bean, "getRuntimeDirWithAuth"));
                        addIfNonEmpty(candidates, safeCallString(bean, "getRuntimeDirWithAuthWhite"));
                        addIfNonEmpty(candidates, safeCallString(bean, "getRuntimeDirWithOutAuth"));

                        String bestExistingPath = firstExisting(candidates);
                        if (REWRITE_THEME_MANAGER_WIDGET_PATHS && bestExistingPath != null && !bestExistingPath.equals(currentPath)) {
                            safeSetObjectField(widget, "mPath", bestExistingPath);
                            currentPath = bestExistingPath;
                            log("ThemeManager widget path rewritten to " + bestExistingPath);
                        }

                        if (extra != null) {
                            String extraMtz = bundleGet(extra, "mtzSnapshotPath");
                            if (isEmpty(extraMtz) && !isEmpty(currentPath)) {
                                extra.putString("mtzSnapshotPath", currentPath);
                            }
                            String metaPath = safeCallString(bean, "getMetaPath");
                            if (!isEmpty(metaPath) && isEmpty(bundleGet(extra, "metaPath"))) {
                                extra.putString("metaPath", metaPath);
                            }
                            String metaSnapshotPath = safeCallString(bean, "getMetaSnapshotPath");
                            if (!isEmpty(metaSnapshotPath) && isEmpty(bundleGet(extra, "mrmSnapshotPath"))) {
                                extra.putString("mrmSnapshotPath", metaSnapshotPath);
                            }
                        }

                        if (isEmpty(currentConfigPath)) {
                            String configCandidate = firstNonEmpty(
                                    safeCallString(bean, "getMamlEditConfigPath"),
                                    safeCallString(bean, "getRuntimeEditConfigPath")
                            );
                            if (!isEmpty(configCandidate)) {
                                safeSetObjectField(widget, "mConfigPath", configCandidate);
                            }
                        }

                        if (LOG_VERBOSE) {
                            log("ThemeManager widget built:"
                                    + " id=" + safeIntField(widget, "k")
                                    + " path=" + safeCallString(widget, "getPath")
                                    + " config=" + safeCallString(widget, "getConfigPath")
                                    + " mtzSnapshotPath=" + bundleGet(extra, "mtzSnapshotPath")
                                    + " beanResLocalPath=" + safeCallString(bean, "getResLocalPath")
                                    + " beanResSnapshotPath=" + safeCallString(bean, "getResSnapshotPath"));
                        }
                    }
                }
        ));

        hookSafe(() -> XposedHelpers.findAndHookMethod(
                "com.rearScreen.manager.RearScreenCenterManager",
                classLoader,
                "x2",
                ArrayList.class,
                XposedHelpers.findClass("com.xiaomi.subscreencenter.service.ISubScreen", classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!LOG_VERBOSE) {
                            return;
                        }
                        Object widgetList = param.args[0];
                        log("ThemeManager sending widgets=" + widgetList);
                    }
                }
        ));

        hookSafe(() -> XposedHelpers.findAndHookMethod(
                "com.rearScreen.manager.RearScreenResOperationHelper$Companion",
                classLoader,
                "k",
                boolean.class,
                XposedHelpers.findClass("com.rearScreen.bean.RearScreenListItemBean", classLoader),
                XposedHelpers.findClass("com.rearScreen.maml.MAMLCacheHelper", classLoader),
                XposedHelpers.findClass("kotlin.coroutines.Continuation", classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.getResult() instanceof Boolean)) {
                            return;
                        }
                        boolean result = (Boolean) param.getResult();
                        if (!result) {
                            Object bean = param.args[1];
                            log("ThemeManager apply returned false"
                                    + " resId=" + safeCallString(bean, "getResId")
                                    + " applyId=" + safeCallString(bean, "getApplyId")
                                    + " resLocalPath=" + safeCallString(bean, "getResLocalPath")
                                    + " resSnapshotPath=" + safeCallString(bean, "getResSnapshotPath")
                                    + " metaPath=" + safeCallString(bean, "getMetaPath")
                                    + " metaSnapshotPath=" + safeCallString(bean, "getMetaSnapshotPath")
                                    + " rightPath=" + safeCallString(bean, "getRightPath"));
                        }
                    }
                }
        ));

        hookSafe(() -> XposedHelpers.findAndHookMethod(
                "com.rearScreen.bean.RearScreenListItemBean",
                classLoader,
                "getRuntimeDirWithAuthWhite",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!REDIRECT_WHITE_RUNTIME_PATHS_FOR_LOOSE_AI) {
                            return;
                        }

                        Object bean = param.thisObject;
                        if (bean == null || !shouldUseStandardRuntimePath(bean)) {
                            return;
                        }

                        String whitePath = param.getResult() instanceof String ? (String) param.getResult() : null;
                        String authPath = safeCallString(bean, "getRuntimeDirWithAuth");
                        if (isEmpty(authPath) || authPath.equals(whitePath)) {
                            return;
                        }

                        param.setResult(authPath);
                        if (LOG_VERBOSE) {
                            log("ThemeManager white runtime path redirected to " + authPath
                                    + " for resId=" + safeCallString(bean, "getResId")
                                    + " source=" + safeCallString(bean, "getResLocalPath"));
                        }
                    }
                }
        ));

        hookSafe(() -> XposedHelpers.findAndHookMethod(
                "com.rearScreen.manager.RearScreenResOperationHelper$Companion$apply$applyResult$1",
                classLoader,
                "invokeSuspend",
                Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object bean = safeObjectField(param.thisObject, "$bean");
                        if (bean == null) {
                            return;
                        }
                        ensureThemeManagerSnapshotPaths(bean);
                        if (!STAGE_RIGHT_FILE_BEFORE_APPLY) {
                            return;
                        }
                        stageRightFileIfNeeded(bean, classLoader);
                        stageMtzFileIfNeeded(bean);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object bean = safeObjectField(param.thisObject, "$bean");
                        Object result = param.getResult();
                        if (!(result instanceof Boolean) || bean == null) {
                            return;
                        }
                        log("ThemeManager apply core result="
                                + result
                                + " resId=" + safeCallString(bean, "getResId")
                                + " applyId=" + safeCallString(bean, "getApplyId")
                                + " rightPath=" + safeCallString(bean, "getRightPath")
                                + " resSnapshotPath=" + safeCallString(bean, "getResSnapshotPath"));
                    }
                }
        ));
    }

    private void hookSubScreenCenter(ClassLoader classLoader) {
        log("Hooking SubScreenCenter");

        hookSafe(() -> XposedHelpers.findAndHookMethod(
                "m2.a",
                classLoader,
                "d",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String resolved = (String) param.getResult();
                        if (!isEmpty(resolved)) {
                            return;
                        }

                        Object widget = param.thisObject;
                        Bundle extra = safeBundleField(widget, "d");
                        String fallback = firstExisting(
                                safeStringField(widget, "f"),
                                bundleGet(extra, "mtzSnapshotPath"),
                                bundleGet(extra, "snapshotPath"),
                                bundleGet(extra, "metaPath"),
                                bundleGet(extra, "mrmSnapshotPath")
                        );

                        if (fallback == null && FORCE_ACCEPT_WIDGETS_IN_SUBSCREEN) {
                            fallback = firstNonEmpty(
                                    safeStringField(widget, "f"),
                                    bundleGet(extra, "mtzSnapshotPath"),
                                    bundleGet(extra, "snapshotPath"),
                                    bundleGet(extra, "metaPath"),
                                    bundleGet(extra, "mrmSnapshotPath")
                            );
                        }

                        if (!isEmpty(fallback)) {
                            param.setResult(fallback);
                            log("SubScreenCenter m2.a.d() forced path=" + fallback);
                        }
                    }
                }
        ));

        hookSafe(() -> XposedHelpers.findAndHookMethod(
                "Z1.g",
                classLoader,
                "test",
                Object.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!FORCE_ACCEPT_WIDGETS_IN_SUBSCREEN) {
                            return;
                        }

                        int predicateType = safeIntField(param.thisObject, "a");
                        if (predicateType != 3) {
                            return;
                        }

                        Object widget = param.args[0];
                        if (widget == null || !"m2.a".equals(widget.getClass().getName())) {
                            return;
                        }

                        boolean invalid = (Boolean) param.getResult();
                        if (!invalid) {
                            return;
                        }

                        Bundle extra = safeBundleField(widget, "d");
                        String forced = firstNonEmpty(
                                safeStringField(widget, "f"),
                                bundleGet(extra, "mtzSnapshotPath"),
                                bundleGet(extra, "snapshotPath"),
                                bundleGet(extra, "metaPath"),
                                bundleGet(extra, "mrmSnapshotPath")
                        );

                        if (!isEmpty(forced)) {
                            param.setResult(false);
                            log("SubScreenCenter widget kept despite validator rejection:"
                                    + " type=" + safeIntField(widget, "b")
                                    + " path=" + safeStringField(widget, "f")
                                    + " mtzSnapshotPath=" + bundleGet(extra, "mtzSnapshotPath"));
                        }
                    }
                }
        ));

        hookSafe(() -> XposedHelpers.findAndHookMethod(
                "B0.d",
                classLoader,
                "K",
                List.class,
                int.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!LOG_VERBOSE) {
                            return;
                        }
                        @SuppressWarnings("unchecked")
                        List<Object> widgets = (List<Object>) param.args[0];
                        int source = (Integer) param.args[1];
                        log("SubScreenCenter B0.d.K source=" + source + " size=" + (widgets == null ? 0 : widgets.size()));
                        if (widgets == null) {
                            return;
                        }
                        for (Object widget : widgets) {
                            Bundle extra = safeBundleField(widget, "d");
                            log("  widget type=" + safeIntField(widget, "b")
                                    + " path=" + safeStringField(widget, "f")
                                    + " config=" + safeStringField(widget, "g")
                                    + " mtzSnapshotPath=" + bundleGet(extra, "mtzSnapshotPath"));
                        }
                    }
                }
        ));
    }

    private interface HookAction {
        void run() throws Throwable;
    }

    private static void hookSafe(HookAction action) {
        try {
            action.run();
        } catch (Throwable t) {
            log("Hook setup failed: " + Log.getStackTraceString(t));
        }
    }

    private static void addIfNonEmpty(List<String> out, String value) {
        if (!isEmpty(value)) {
            out.add(value);
        }
    }

    private static void ensureThemeManagerSnapshotPaths(Object bean) {
        if (!PREFILL_THEME_MANAGER_SNAPSHOT_PATHS) {
            return;
        }

        String resLocalPath = safeCallString(bean, "getResLocalPath");
        String resSnapshotPath = safeCallString(bean, "getResSnapshotPath");
        if (isEmpty(resSnapshotPath) && fileExists(resLocalPath)) {
            safeCallMethod(bean, "setResSnapshotPath", resLocalPath);
            if (LOG_VERBOSE) {
                log("ThemeManager bean resSnapshotPath filled from resLocalPath " + resLocalPath);
            }
        }

        String metaPath = safeCallString(bean, "getMetaPath");
        String metaSnapshotPath = safeCallString(bean, "getMetaSnapshotPath");
        if (isEmpty(metaSnapshotPath) && fileExists(metaPath)) {
            safeCallMethod(bean, "setMetaSnapshotPath", metaPath);
            if (LOG_VERBOSE) {
                log("ThemeManager bean metaSnapshotPath filled from metaPath " + metaPath);
            }
        }
    }

    private static void stageRightFileIfNeeded(Object bean, ClassLoader classLoader) {
        String sourceRightPath = safeCallString(bean, "getRightPath");
        if (isEmpty(sourceRightPath) || !sourceRightPath.endsWith(".mra")) {
            return;
        }

        String resLocalPath = safeCallString(bean, "getResLocalPath");
        if (isEmpty(resLocalPath) || !resLocalPath.contains("/rearscreen/")) {
            return;
        }

        String rightsBaseDir = safeStaticStringField(
                classLoader,
                "com.android.thememanager.basemodule.resource.constants.ThemeResourceConstants",
                "cmzf"
        );
        if (isEmpty(rightsBaseDir)) {
            log("ThemeManager right-file staging skipped: cmzf path missing for " + sourceRightPath);
            return;
        }

        String fileName = new File(sourceRightPath).getName();
        if (isEmpty(fileName)) {
            return;
        }
        if (!fileName.startsWith("rearscreen_")) {
            fileName = "rearscreen_" + fileName;
        }

        String destinationRightPath = ensureTrailingSlash(rightsBaseDir) + fileName;
        if (sourceRightPath.equals(destinationRightPath)) {
            return;
        }

        File destinationFile = new File(destinationRightPath);
        if (destinationFile.exists()) {
            destinationFile.setReadable(true, false);
            destinationFile.setWritable(true, false);
            destinationFile.setExecutable(true, false);
            safeCallMethod(bean, "setRightPath", destinationRightPath);
            if (LOG_VERBOSE) {
                log("ThemeManager bean rightPath rewritten to existing destination " + destinationRightPath);
            }
            return;
        }

        File sourceFile = new File(sourceRightPath);
        if (!sourceFile.exists()) {
            if (ALLOW_DONOR_RIGHT_FILE_FALLBACK) {
                File donorFile = findLatestRightFile(rightsBaseDir, destinationRightPath);
                if (donorFile != null) {
                    try {
                        copyFile(donorFile, destinationFile);
                        destinationFile.setReadable(true, false);
                        destinationFile.setWritable(true, false);
                        destinationFile.setExecutable(true, false);
                        safeCallMethod(bean, "setRightPath", destinationRightPath);
                        log("ThemeManager donor right file staged " + donorFile.getAbsolutePath()
                                + " -> " + destinationRightPath);
                        return;
                    } catch (IOException e) {
                        log("ThemeManager donor right-file staging failed " + donorFile.getAbsolutePath()
                                + " -> " + destinationRightPath
                                + " error=" + Log.getStackTraceString(e));
                    }
                }
            }
            if (BYPASS_MISSING_RIGHT_FILE_COPY) {
                safeCallMethod(bean, "setRightPath", destinationRightPath);
                log("ThemeManager right-file copy bypassed: source missing "
                        + sourceRightPath
                        + ", using destination path "
                        + destinationRightPath);
                return;
            }
            log("ThemeManager right-file staging skipped: source missing " + sourceRightPath);
            return;
        }

        if (!destinationFile.exists()) {
            try {
                copyFile(sourceFile, destinationFile);
                destinationFile.setReadable(true, false);
                destinationFile.setWritable(true, false);
                destinationFile.setExecutable(true, false);
                log("ThemeManager staged right file " + sourceRightPath + " -> " + destinationRightPath);
            } catch (IOException e) {
                log("ThemeManager right-file staging failed " + sourceRightPath + " -> " + destinationRightPath
                        + " error=" + Log.getStackTraceString(e));
                return;
            }
        }

        safeCallMethod(bean, "setRightPath", destinationRightPath);
        if (LOG_VERBOSE) {
            log("ThemeManager bean rightPath rewritten to " + destinationRightPath);
        }
    }

    private static void stageMtzFileIfNeeded(Object bean) {
        if (!STAGE_MTZ_FILE_BEFORE_APPLY) {
            return;
        }

        String sourceMtzPath = safeCallString(bean, "getResLocalPath");
        if (isEmpty(sourceMtzPath)) {
            return;
        }

        File sourceFile = new File(sourceMtzPath);
        if (!sourceFile.exists()) {
            log("ThemeManager mtz staging skipped: source missing " + sourceMtzPath);
            return;
        }

        if (sourceFile.isDirectory() || !sourceMtzPath.endsWith(".mrc")) {
            stageLooseMtzSourceIfNeeded(bean, sourceFile);
            return;
        }

        if (!isPrebuiltRearThemePath(sourceMtzPath)) {
            return;
        }

        String destinationMtzPath = safeCallString(bean, "getRuntimeDirWithAuth");
        if (isEmpty(destinationMtzPath)) {
            log("ThemeManager mtz staging skipped: runtime auth path missing for " + sourceMtzPath);
            return;
        }

        File destinationFile = new File(destinationMtzPath);
        if (!destinationFile.exists()) {
            try {
                copyFile(sourceFile, destinationFile);
                destinationFile.setReadable(true, false);
                destinationFile.setWritable(true, false);
                destinationFile.setExecutable(true, false);
                log("ThemeManager staged mtz file " + sourceMtzPath + " -> " + destinationMtzPath);
            } catch (IOException e) {
                log("ThemeManager mtz staging failed " + sourceMtzPath + " -> " + destinationMtzPath
                        + " error=" + Log.getStackTraceString(e));
                return;
            }
        }

        safeCallMethod(bean, "setResLocalPath", destinationMtzPath);
        safeCallMethod(bean, "setResSnapshotPath", destinationMtzPath);
        if (LOG_VERBOSE) {
            log("ThemeManager bean mtz paths rewritten to " + destinationMtzPath);
        }
    }

    private static void stageLooseMtzSourceIfNeeded(Object bean, File sourceFile) {
        String sourcePath = sourceFile.getAbsolutePath();
        String destinationMtzPath = resolveRuntimeMtzPath(bean);
        if (isEmpty(destinationMtzPath)) {
            log("ThemeManager loose mtz staging skipped: runtime path missing for " + sourcePath);
            return;
        }

        File destinationFile = new File(destinationMtzPath);
        try {
            if (sourceFile.isDirectory()) {
                zipDirectoryContents(sourceFile, destinationFile);
                log("ThemeManager packaged directory mtz source " + sourcePath + " -> " + destinationMtzPath);
            } else {
                copyFile(sourceFile, destinationFile);
                log("ThemeManager staged loose mtz source " + sourcePath + " -> " + destinationMtzPath);
            }
            destinationFile.setReadable(true, false);
            destinationFile.setWritable(true, false);
            destinationFile.setExecutable(true, false);
        } catch (IOException e) {
            log("ThemeManager loose mtz staging failed " + sourcePath + " -> " + destinationMtzPath
                    + " error=" + Log.getStackTraceString(e));
            return;
        }

        safeCallMethod(bean, "setResLocalPath", destinationMtzPath);
        safeCallMethod(bean, "setResSnapshotPath", destinationMtzPath);
        if (LOG_VERBOSE) {
            log("ThemeManager bean mtz paths rewritten to runtime path " + destinationMtzPath);
        }
    }

    private static String resolveRuntimeMtzPath(Object bean) {
        String preferred = safeCallString(bean, "getRuntimeDirWithAuthWhite");
        if (!isEmpty(preferred)) {
            return preferred;
        }
        return safeCallString(bean, "getRuntimeDirWithAuth");
    }

    private static boolean shouldUseStandardRuntimePath(Object bean) {
        String resSubType = safeCallString(bean, "getResSubType");
        if ("ai".equals(resSubType)) {
            return true;
        }

        String resLocalPath = safeCallString(bean, "getResLocalPath");
        if (isEmpty(resLocalPath)) {
            return false;
        }
        if (resLocalPath.contains("/.ai_wallpaper/")) {
            return true;
        }
        if (resLocalPath.startsWith("/data/system/theme/rearScreen/")) {
            return false;
        }

        File sourceFile = new File(resLocalPath);
        if (sourceFile.isDirectory()) {
            return true;
        }
        return !resLocalPath.endsWith(".mrc");
    }

    private static String bundleGet(Bundle bundle, String key) {
        if (bundle == null) {
            return null;
        }
        try {
            return bundle.getString(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bundle safeCallBundle(Object target, String method) {
        try {
            return (Bundle) XposedHelpers.callMethod(target, method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bundle safeBundleField(Object target, String field) {
        try {
            return (Bundle) XposedHelpers.getObjectField(target, field);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeCallString(Object target, String method) {
        try {
            return (String) XposedHelpers.callMethod(target, method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeStringField(Object target, String field) {
        try {
            Object value = XposedHelpers.getObjectField(target, field);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object safeObjectField(Object target, String field) {
        try {
            return XposedHelpers.getObjectField(target, field);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int safeIntField(Object target, String field) {
        try {
            return XposedHelpers.getIntField(target, field);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static String safeStaticStringField(ClassLoader classLoader, String className, String fieldName) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object safeCallMethod(Object target, String method, Object... args) {
        try {
            return XposedHelpers.callMethod(target, method, args);
        } catch (Throwable t) {
            log("Failed to call method " + method + ": " + t);
            return null;
        }
    }

    private static void safeSetObjectField(Object target, String field, Object value) {
        try {
            XposedHelpers.setObjectField(target, field, value);
        } catch (Throwable t) {
            log("Failed to set field " + field + ": " + t);
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Failed to create directory " + parent);
        }

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static void zipDirectoryContents(File sourceDir, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Failed to create directory " + parent);
        }

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(destination))) {
            addDirectoryToZip(sourceDir, sourceDir, output);
            output.finish();
        }
    }

    private static void addDirectoryToZip(File rootDir, File current, ZipOutputStream output) throws IOException {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }

        if (children.length == 0 && !rootDir.equals(current)) {
            ZipEntry entry = new ZipEntry(toZipEntryName(rootDir, current) + "/");
            output.putNextEntry(entry);
            output.closeEntry();
            return;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                addDirectoryToZip(rootDir, child, output);
                continue;
            }

            ZipEntry entry = new ZipEntry(toZipEntryName(rootDir, child));
            output.putNextEntry(entry);
            try (FileInputStream input = new FileInputStream(child)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            output.closeEntry();
        }
    }

    private static String toZipEntryName(File rootDir, File file) {
        String rootPath = rootDir.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        String relativePath = filePath.substring(rootPath.length());
        if (relativePath.startsWith(File.separator)) {
            relativePath = relativePath.substring(1);
        }
        return relativePath.replace(File.separatorChar, '/');
    }

    private static File findLatestRightFile(String rightsBaseDir, String excludePath) {
        if (isEmpty(rightsBaseDir)) {
            return null;
        }

        File dir = new File(rightsBaseDir);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }

        File best = null;
        long bestModified = Long.MIN_VALUE;
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (!name.startsWith("rearscreen_") || !name.endsWith(".mra")) {
                continue;
            }
            if (!isEmpty(excludePath) && excludePath.equals(file.getAbsolutePath())) {
                continue;
            }
            long modified = file.lastModified();
            if (best == null || modified > bestModified) {
                best = file;
                bestModified = modified;
            }
        }
        return best;
    }

    private static String firstExisting(String... candidates) {
        return firstExisting(Arrays.asList(candidates));
    }

    private static String firstExisting(List<String> candidates) {
        for (String candidate : candidates) {
            if (!isEmpty(candidate) && new File(candidate).exists()) {
                return candidate;
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... candidates) {
        for (String candidate : candidates) {
            if (!isEmpty(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean fileExists(String value) {
        return !isEmpty(value) && new File(value).exists();
    }

    private static boolean isPrebuiltRearThemePath(String value) {
        return value.startsWith("/product/")
                || value.startsWith("/system/")
                || value.startsWith("/vendor/");
    }

    private static String ensureTrailingSlash(String value) {
        if (isEmpty(value) || value.endsWith("/")) {
            return value;
        }
        return value + "/";
    }

    private static void log(String message) {
        String line = TAG + ": " + message;
        XposedBridge.log(line);
        Log.i(TAG, message);
    }
}

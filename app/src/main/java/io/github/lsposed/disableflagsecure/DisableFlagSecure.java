package io.github.lsposed.disableflagsecure;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public class DisableFlagSecure extends XposedModule {
    /**
     * 实例化新的 Xposed 模块。<br/>
     * 当模块加载到目标进程中时，将调用构造函数。
     *
     * @param基础框架提供的实现接口，模块不宜使用
     * @param参数 有关模块加载过程的信息
     */
    public DisableFlagSecure(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
        super(base, param);
    }

    // 构造函数


    /**
     * 系统服务加载时的处理逻辑。
     * 主要用于反射调用和修改系统内部行为，以禁用屏幕安全标志。
     *
     * @param param 包含系统服务类加载器的对象。
     */
    @Override
    public void onSystemServerLoaded(@NonNull SystemServerLoadedParam param) {
        var classLoader = param.getClassLoader();

        // 尝试对系统服务器进行去优化处理，以改变特定方法的行为
        try {
            deoptimizeSystemServer(classLoader);
        } catch (Throwable t) {
            log("deoptimize system server failed", t);
        }
        // 尝试钩子WindowState相关方法，以影响窗口安全属性的判断
        try {
            hookWindowState(classLoader);
        } catch (Throwable t) {
            log("hook WindowState failed", t);
        }

        // 钩子HyperOS相关方法，实现特定功能
        try {
            hookHyperOS(classLoader);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            log("hook HyperOS failed", t);
        }

        // 在特定Android版本上，额外钩子ActivityTaskManagerService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                hookActivityTaskManagerService(classLoader);
            } catch (Throwable t) {
                log("hook ActivityTaskManagerService failed", t);
            }
        }
    }

    /**
     * 包加载时的处理逻辑。
     * 主要用于对特定应用包名的处理，如Flyme和Oplus的截图功能的定制修改。
     *
     * @param param 包含包名和类加载器的对象。
     */
    @SuppressLint("PrivateApi")
    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!param.isFirstPackage()) return; // 仅处理第一个加载的包

        var classLoader = param.getClassLoader();
        // 根据不同的包名，执行不同的钩子逻辑
        switch (param.getPackageName()) {
            case "com.flyme.systemuiex":
                try {
                    hookFlyme(classLoader);
                } catch (Throwable t) {
                    log("hook Flyme failed", t);
                }
                break;
            case "com.oplus.screenshot":
                try {
                    hookOplus(classLoader);
                } catch (Throwable t) {
                    log("hook OPlus failed", t);
                }
                break;
            default:
                // 默认尝试对Activity的onResume方法进行钩子，展示提示信息
                try {
                    hookOnResume();
                } catch (Throwable ignored) {
                }
        }
    }

    /**
     * 对系统服务器相关类的方法进行去优化处理，
     * 以改变其默认行为，达到禁用安全标志的目的。
     *
     * @param classLoader 要处理的类的类加载器。
     * @throws ClassNotFoundException 如果指定类未找到。
     */
    private void deoptimizeSystemServer(ClassLoader classLoader) throws ClassNotFoundException {
        // 对WindowStateAnimator和WindowManagerService的特定方法进行去优化处理
        deoptimizeMethods(
                classLoader.loadClass("com.android.server.wm.WindowStateAnimator"),
                "createSurfaceLocked");

        deoptimizeMethods(
                classLoader.loadClass("com.android.server.wm.WindowManagerService"),
                "relayoutWindow");

        // 对外部合成Lambda类进行遍历和去优化处理
        for (int i = 0; i < 20; i++) {
            try {
                var clazz = classLoader.loadClass("com.android.server.wm.RootWindowContainer$$ExternalSyntheticLambda" + i);
                if (BiConsumer.class.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "accept");
                }
            } catch (ClassNotFoundException ignored) {
            }
            try {
                var clazz = classLoader.loadClass("com.android.server.wm.DisplayContent$$ExternalSyntheticLambda" + i);
                if (BiPredicate.class.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "test");
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    /**
     * 对指定类的特定方法进行去优化处理。
     *
     * @param clazz 要处理的类。
     * @param names 要去优化的方法名列表。
     */
    private void deoptimizeMethods(Class<?> clazz, String... names) {
        var list = Arrays.asList(names);
        // 筛选出方法名匹配的方法并进行去优化处理
        Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> list.contains(method.getName()))
                .forEach(this::deoptimize);
    }

    /**
     * 钩子WindowState类的相关方法，以修改窗口的安全性判断。
     *
     * @param classLoader 要处理的类的类加载器。
     * @throws ClassNotFoundException 如果指定类未找到。
     * @throws NoSuchMethodException  如果指定方法未找到。
     */
    private void hookWindowState(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var windowStateClazz = classLoader.loadClass("com.android.server.wm.WindowState");
        Method isSecureLockedMethod;
        // 根据Android版本选择合适的方法进行钩子
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isSecureLockedMethod = windowStateClazz.getDeclaredMethod("isSecureLocked");
        } else {
            var windowManagerServiceClazz = classLoader.loadClass("com.android.server.wm.WindowManagerService");
            isSecureLockedMethod = windowManagerServiceClazz.getDeclaredMethod("isSecureLocked", windowStateClazz);
        }
        hook(isSecureLockedMethod, ReturnFalseHooker.class);
    }

    /**
     * 在Android特定版本上钩子ActivityTaskManagerService相关方法。
     *
     * @param classLoader 要处理的类的类加载器。
     * @throws ClassNotFoundException 如果指定类未找到。
     * @throws NoSuchMethodException  如果指定方法未找到。
     */
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void hookActivityTaskManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var activityTaskManagerServiceClazz = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService");
        var iBinderClazz = classLoader.loadClass("android.os.IBinder");
        var iScreenCaptureObserverClazz = classLoader.loadClass("android.app.IScreenCaptureObserver");
        var method = activityTaskManagerServiceClazz.getDeclaredMethod("registerScreenCaptureObserver", iBinderClazz, iScreenCaptureObserverClazz);
        hook(method, ReturnNullHooker.class);
    }

    /**
     * 钩子HyperOS相关方法，以实现特定功能。
     *
     * @param classLoader 要处理的类的类加载器。
     * @throws ClassNotFoundException 如果指定类未找到。
     */
    private void hookHyperOS(ClassLoader classLoader) throws ClassNotFoundException {
        var windowManagerServiceImplClazz = classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl");
        hookMethods(windowManagerServiceImplClazz, ReturnFalseHooker.class, "notAllowCaptureDisplay");
    }

    /**
     * 钩子Flyme相关方法，以禁用其截图中的安全层检查。
     *
     * @param classLoader 要处理的类的类加载器。
     * @throws ClassNotFoundException 如果指定类未找到。
     * @throws NoSuchMethodException  如果指定方法未找到。
     */
    private void hookFlyme(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var screenshotHardwareBufferClazz = classLoader.loadClass("android.view.SurfaceControl$ScreenshotHardwareBuffer");
        var method = screenshotHardwareBufferClazz.getDeclaredMethod("containsSecureLayers");
        hook(method, ReturnFalseHooker.class);
    }

    /**
     * 钩子Oplus相关方法，以禁用其截图功能中的特定逻辑。
     *
     * @param classLoader 要处理的类的类加载器。
     * @throws ClassNotFoundException 如果指定类未找到。
     * @throws NoSuchMethodException  如果指定方法未找到。
     */
    private void hookOplus(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var screenshotContextClazz = classLoader.loadClass("com.oplus.screenshot.screenshot.core.ScreenshotContext");
        hookMethods(screenshotContextClazz, ReturnNullHooker.class, "setScreenshotReject", "setLongshotReject");
    }

    /**
     * 钩子所有Activity的onResume方法，以展示特定的Toast信息。
     *
     * @throws NoSuchMethodException 如果指定方法未找到。
     */
    private void hookOnResume() throws NoSuchMethodException {
        var method = Activity.class.getDeclaredMethod("onResume");
        hook(method, ToastHooker.class);
    }

    /**
     * 对指定类的特定方法应用钩子，使用给定的Hooker类处理。
     *
     * @param clazz  要处理的类。
     * @param hooker 用于处理方法调用的Hooker类。
     * @param names  要钩子的方法名列表。
     */
    private void hookMethods(Class<?> clazz, Class<? extends Hooker> hooker, String... names) {
        var list = Arrays.asList(names);
        // 筛选出方法名匹配的方法并应用钩子
        Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> list.contains(method.getName()))
                .forEach(method -> hook(method, hooker));
    }

    // 定义一个空的Hooker接口，用于在方法调用前执行特定逻辑
    @XposedHooker
    private static class ReturnFalseHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            callback.returnAndSkip(false);
        }
    }

    // 定义一个空的Hooker接口，用于在方法调用前返回null
    @XposedHooker
    private static class ReturnNullHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            callback.returnAndSkip(null);
        }
    }

    // 定义一个空的Hooker接口，用于在Activity的onResume方法调用前展示Toast信息并结束当前Activity
    @XposedHooker
    private static class ToastHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            var activity = (Activity) callback.getThisObject();
            assert activity != null;
            Toast.makeText(activity, "DFS: Incorrect module usage, remove this app from scope.", Toast.LENGTH_LONG).show();
            activity.finish();
        }
    }
}

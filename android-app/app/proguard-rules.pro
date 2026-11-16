# ProGuard keep rules for JNI-invoked callback classes, Application, diagnostics, and Parcelables
-keepattributes SourceFile,LineNumberTable
-keep class com.sillytavern.app.SillyTavernApplication { *; }
-keep class com.sillytavern.app.diag.** { *; }
-keep class com.sillytavern.app.engine.NativeNodeEngine { *; }
-keep interface com.sillytavern.app.engine.EngineLifecycleCallback { *; }
-keep class com.sillytavern.app.bridge.ExportBridge {
    @android.webkit.JavascriptInterface <methods>;
}

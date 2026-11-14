# ProGuard keep rules for JNI-invoked callback classes and Parcelables
-keep class com.sillytavern.app.engine.NativeNodeEngine { *; }
-keep interface com.sillytavern.app.engine.EngineLifecycleCallback { *; }
-keep class com.sillytavern.app.bridge.ExportBridge {
    @android.webkit.JavascriptInterface <methods>;
}

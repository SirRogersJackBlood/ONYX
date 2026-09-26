# libsignal: JNI calls back into these by name.
-keep class org.signal.libsignal.** { *; }
# Tor JNI + control library
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
# gomobile-generated IPtProxy bindings
-keep class IPtProxy.** { *; }
-keep class go.** { *; }
# Strip all logging from release builds: nothing about contacts/messages may hit logcat.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

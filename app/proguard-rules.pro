# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# JNI boundary: native code resolves these by fully-qualified name
# (Java_com_kevingraney_c47_engine_C47Engine_nativeX). Renaming the class
# or its external methods would break the link at runtime.
-keep class com.kevingraney.c47.engine.C47Engine {
    native <methods>;
}
-keepclassmembers class com.kevingraney.c47.engine.C47Engine { *; }
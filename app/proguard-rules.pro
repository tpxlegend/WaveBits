# The Web Player page calls the host through this @JavascriptInterface on WebViews without
# web message listeners (PlayerChannel.kt).
-keepclassmembers class app.wavebits.host.android.PlayerChannel {
    @android.webkit.JavascriptInterface <methods>;
}
# Bundled resources read by name (bridge script, GraphQL operation table).
-keep class app.wavebits.spotify.bridge.BridgeScript { *; }
-keep class app.wavebits.spotify.gql.GqlOps { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.annotation.concurrent.GuardedBy
-keepclassmembers enum * { *; }
-keepclassmembers class * implements java.io.Serializable { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepattributes *Annotation*,JavascriptInterface,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.protos.** { *; }
-keep class ** extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class ** extends com.google.protobuf.GeneratedMessage { *; }
-keepclassmembers class ** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class ** extends com.google.protobuf.GeneratedMessage {
    <fields>;
}

# NewPipe / Rhino - java.beans.* not available on Android on device
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn javax.script.**
# do not add -keep class org.mozilla.javascript.** - it pulls in classes
# referencing java.beans.* which breaks ART install-time verification

-dontwarn java.lang.instrument.**

-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.work.** {
    <init>(...);
}
-keep class * extends androidx.glance.appwidget.action.ActionCallback {
    <init>();
}

# Native UI + Spotify data layer
-keep class com.music.spotui.data.entity.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# NewPipeExtractor (YouTube music videos): keep only NewPipeExtractor reflection targets;
# do NOT keep org.mozilla.javascript.** broadly as its tools/beans classes pull java.beans.*
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn jdk.dynalink.**

# ML Kit (the Jam QR scanner) creates its component registrars by reflection: without their
# no-argument constructors it fails to start and the scan crashes.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { *; }

# Extensions: a Spotify call's result is turned into JSON from the API models' own field names
# (SpotifyDispatcher reads them by reflection), and the sandbox loads prelude.js from its jar.
-keepclassmembers class app.wavebits.api.** { <fields>; }
-keep class app.wavebits.extensions.** { *; }

# The relay's Cronet (before Android 14): CronetProvider finds the Play services provider by its
# class name, and zstd's decoder is reached from its native library by JNI.
-keep class com.google.android.gms.net.PlayServicesCronetProvider { *; }
-keep class com.google.android.gms.net.GmsCoreCronetProvider { *; }
-keep class com.squareup.zstd.** { *; }

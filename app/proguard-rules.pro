# RUTA release shrinker/obfuscation rules.
#
# R8 runs for the release build (isMinifyEnabled = true). RUTA leans on runtime
# reflection in several places, so these rules are deliberately conservative:
# we keep reflected entry points and names instead of trading correctness for a
# few kilobytes. No aggressive obfuscation flags (-repackageclasses,
# -overloadaggressively, -allowaccessmodification, custom dictionaries, ...) are
# used on purpose.
#
# Firebase (Auth, Realtime Database, Storage) and Google Play Services AARs ship
# their own consumer ProGuard rules, which AGP merges automatically. Only the
# pieces those libraries cannot know about (our own models, our app's generic
# signatures, optional dependencies) are listed below.

# ---------------------------------------------------------------------------
# Crash reporting: keep the original source file name and line numbers.
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Reflection metadata required by Gson / Retrofit / Firebase.
#   - Signature: generic parameter info (Gson, Retrofit converters)
#   - *Annotation*: @SerializedName, @retrofit2.http.*, Firebase annotations
#   - InnerClasses / EnclosingMethod: nested classes inspected reflectively
#   - Exceptions: Retrofit service method exception signatures
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses, EnclosingMethod
-keepattributes Exceptions

# Keep anything explicitly opted out of shrinking.
-keep @androidx.annotation.Keep class * { *; }

# ---------------------------------------------------------------------------
# RUTA's own data models.
#
# Firebase Realtime Database deserialises these with reflection, matching JSON
# keys to Kotlin property names, and instantiates them through the synthesised
# no-arg constructor (every property has a default). Renaming any class, field
# or constructor would silently break every read and write, so keep them whole.
# ---------------------------------------------------------------------------
# Covers Booking, BookingModel, DiscountRequest, User, SavedPlace and UserRole.
-keep class com.ruta.app.model.** { *; }
# ChatMessage lives in com.ruta.app.ui, not the model package.
-keep class com.ruta.app.ui.ChatMessage { *; }

# Any RUTA class with a no-arg constructor that Firebase/Gson may instantiate by
# name (defensive: covers models added later that are not under `model`).
-keepclassmembers class com.ruta.app.** {
    <init>();
}

# ---------------------------------------------------------------------------
# Gson reflection targets (Retrofit's converter-gson).
# ---------------------------------------------------------------------------
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ---------------------------------------------------------------------------
# Retrofit 2.x reflection targets.
# ---------------------------------------------------------------------------
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
# R8 full mode sees no subtypes of Retrofit interfaces (they are dynamic proxies)
# and would replace every value with null; keep the annotated interfaces.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# ---------------------------------------------------------------------------
# Glide (its module/enum discovery is reflective).
# ---------------------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ---------------------------------------------------------------------------
# ZXing / journeyapps embedded scanner.
# ---------------------------------------------------------------------------
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# ---------------------------------------------------------------------------
# View binding.
#
# Binding classes are normally referenced directly (no reflection), so this keep
# is defensive rather than strictly required; it prevents a future reflective
# use of an inflate helper from being stripped.
# ---------------------------------------------------------------------------
-keep class com.ruta.app.databinding.** { *; }

# ---------------------------------------------------------------------------
# Optional dependencies referenced by Gson/Retrofit/OkHttp but absent at
# runtime. Suppress the shrinker's missing-class warnings for them.
# ---------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn sun.misc.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn retrofit2.**
-dontwarn okio.**

# ---------------------------------------------------------------------------
# gRPC load-balancer and name-resolver providers referenced by the Places SDK
# but not packaged in the app. R8 flags them as missing classes; they are
# optional at runtime and are never loaded.
# ---------------------------------------------------------------------------
-dontwarn io.grpc.internal.DnsNameResolverProvider
-dontwarn io.grpc.internal.PickFirstLoadBalancerProvider
-dontwarn io.grpc.**

# ---------------------------------------------------------------------------
# Strip debug-level logging and raw stack-trace printing out of the release
# build. Log.e is deliberately kept so genuine errors remain diagnosable in a
# signed release; the app currently makes no log calls at all, so these rules
# exist to keep it that way if one is added later.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

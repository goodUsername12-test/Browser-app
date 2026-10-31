# ============ Fable Browser R8 / ProGuard rules ============

# --- GeckoView ---
# GeckoView uses JNI extensively; keep all of it.
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**
# Keep classes annotated for WrapForJNI
-keepclasseswithmembers class * {
    @org.mozilla.gecko.annotation.WrapForJNI <methods>;
}
-keepclasseswithmembers class * {
    @org.mozilla.gecko.annotation.WrapForJNI <fields>;
}

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- Hilt / Dagger (mostly handled by the plugin, belt-and-suspenders) ---
-dontwarn com.google.dagger.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# --- Kotlin coroutines ---
-dontwarn kotlinx.coroutines.**

# --- Keep data models used in Room / serialization ---
-keep class com.fable.browser.data.model.** { *; }
-keep class com.fable.browser.data.db.** { *; }

# --- Compose (handled by AGP, kept for safety) ---
-dontwarn androidx.compose.**

-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**

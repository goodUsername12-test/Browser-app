-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.geckoview.**
-dontwarn org.mozilla.gecko.**
-keepclasseswithmembers class * {
    @org.mozilla.gecko.annotation.WrapForJNI <methods>;
}
-keepclasseswithmembers class * {
    @org.mozilla.gecko.annotation.WrapForJNI <fields>;
}

-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

-dontwarn com.google.dagger.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

-dontwarn kotlinx.coroutines.**

-keep class com.fable.browser.data.model.** { *; }
-keep class com.fable.browser.data.db.** { *; }

-dontwarn androidx.compose.**

-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**

-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.android.exoplayer2.**
-keep class org.mozilla.thirdparty.** { *; }
-dontwarn org.mozilla.thirdparty.**

-keep class org.yaml.snakeyaml.** { *; }

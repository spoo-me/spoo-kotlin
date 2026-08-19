# Keep kotlinx.serialization generated serializers for this SDK's models.
-keepclassmembers class me.spoo.** {
    *** Companion;
}
-keepclasseswithmembers class me.spoo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class me.spoo.**$$serializer { *; }

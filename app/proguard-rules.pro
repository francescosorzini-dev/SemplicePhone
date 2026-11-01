# kotlinx.serialization: keep generated serializers for config model
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.semplicephone.model.**$$serializer { *; }
-keepclassmembers class app.semplicephone.model.** {
    *** Companion;
}
-keepclasseswithmembers class app.semplicephone.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

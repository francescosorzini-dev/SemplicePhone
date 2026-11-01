# kotlinx.serialization: keep generated serializers for config model
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.semplicephone.**$$serializer { *; }
-keepclassmembers class app.semplicephone.** {
    *** Companion;
}
-keepclasseswithmembers class app.semplicephone.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle: riferimenti opzionali (JNDI, LDAP...) non presenti su Android
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# SPDX-License-Identifier: GPL-3.0-or-later
# M15: rules for apps that use backup:target-smb (applied to the release build's R8 pass).

# smbj's optional features KeepClear never uses: Kerberos (GSS-API) and mbassador's EL filters.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
# slf4j without a logging backend (no logs leave the phone; nothing is bound).
-dontwarn org.slf4j.impl.**

# smbj dispatches its internal events through mbassador, which finds handlers by annotation (reflection).
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.protocol.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
-keep class net.engio.mbassy.** { *; }

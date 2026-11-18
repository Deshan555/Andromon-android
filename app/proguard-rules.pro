# ShellHelper is started by name with app_process (see ShellHelper.kt), not from app code,
# so R8 must keep the class name and its main().
-keep class dev.andromon.companion.ShellHelper {
    public static void main(java.lang.String[]);
}
# ClipWatch (the PC's shared clipboard) is started the same way: app_process, by class name.
-keep class dev.andromon.companion.ClipWatch {
    public static void main(java.lang.String[]);
}

# Privileged access through Shizuku: Shizuku creates PrivilegedService by name, in a process of
# its own, with its no-argument or its Context constructor. Nothing in the app calls them.
-keep class dev.andromon.companion.PrivilegedService {
    public <init>();
    public <init>(android.content.Context);
}
# Shizuku-API's androidx.annotation dependency is left out (build.gradle.kts): annotations only.
-dontwarn androidx.annotation.**

# dnsjava (DnsRecords.kt): build-time annotations, and slf4j's optional logger binding (without one
# slf4j logs nothing, which is what the app wants). Its desktop-only helpers (JNA, JNDI) aren't used.
-dontwarn lombok.**
-dontwarn org.slf4j.impl.**
-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
-dontwarn sun.net.spi.nameservice.**

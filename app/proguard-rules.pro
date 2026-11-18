# ShellHelper is started by name with app_process (see ShellHelper.kt), not from app code,
# so R8 must keep the class name and its main().
-keep class dev.andromon.companion.ShellHelper {
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

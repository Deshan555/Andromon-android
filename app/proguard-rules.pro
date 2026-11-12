# ShellHelper is started by name with app_process (see ShellHelper.kt), not from app code,
# so R8 must keep the class name and its main().
-keep class dev.droiddesk.companion.ShellHelper {
    public static void main(java.lang.String[]);
}

package android.content.pm;

/**
 * Compile-time stub for the hidden {@code android.content.pm.IPackageManager}.
 *
 * Only the one call the module makes is declared. The signature has to match
 * the AIDL exactly — {@code void setPackageStoppedState(in String packageName,
 * boolean stopped, int userId)} — because this is an interface call resolved
 * against the real framework class at runtime; a mismatch surfaces as
 * {@code NoSuchMethodError} inside the hook's catch, not as a compile error.
 */
public interface IPackageManager {
    void setPackageStoppedState(String packageName, boolean stopped, int userId);
}

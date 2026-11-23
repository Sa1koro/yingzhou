package tvbox.fixture;

public final class NativeMath {
    private int value;

    public NativeMath(int value) { this.value = value; }
    private int valuePlus(int increment) { return value + increment; }
    public int value() { return value; }

    public static native int add(int left, int right);
    public static native NativeMath proxyRoundTrip(int value);
    public static native int readAsset(android.content.res.AssetManager manager, String name);
    public static native double unsupported();
    public static native Object echo(Object value);
    public static native Object tag(Object value, String text);
    public static native Object[] pair(Object first, Object second);
    public static native String[] strings(String[] input);
    public static native String loadType(Object application, String name);
    public static native String storageDirectory(Object application, String name);
    public static native int nested(int number);
    public static int callback(int number) { return add(number, 7); }
    public static native String unsupportedContextCall(Object application);

    public static void load(String path) { System.load(path); }
    public static void loadLibrary(String name) { System.loadLibrary(name); }
    public static String abi() { return android.os.Build.SUPPORTED_ABIS[0]; }
}

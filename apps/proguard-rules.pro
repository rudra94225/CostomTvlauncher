-dontwarn android.bluetooth.**
-keepclassmembers class android.bluetooth.BluetoothDevice {
    public boolean isConnected();
}

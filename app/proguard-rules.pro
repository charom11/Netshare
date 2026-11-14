# Add project specific ProGuard rules here.
-keep class com.netshare.wifidirect.** { *; }
# Keep WifiP2pManager hidden reflection methods
-keepclassmembers class android.net.wifi.p2p.WifiP2pManager {
    *;
}


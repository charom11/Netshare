package com.netshare.wifidirect

import android.app.Application
import com.netshare.wifidirect.p2p.WifiDirectManager

class NetShareApp : Application() {

    lateinit var wifiDirectManager: WifiDirectManager
        private set

    override fun onCreate() {
        super.onCreate()
        wifiDirectManager = WifiDirectManager(applicationContext)
    }
}


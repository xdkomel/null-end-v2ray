package com.example.null_end_v2ray.v2ray.interfaces;

import android.app.Service;

public interface V2rayServicesListener {
    boolean onProtect(final int socket);
    Service getService();
    void startService();
    void stopService();
}

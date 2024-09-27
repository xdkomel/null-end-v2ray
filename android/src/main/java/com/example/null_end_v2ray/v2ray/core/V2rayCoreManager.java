package com.example.null_end_v2ray.v2ray.core;

import static com.example.null_end_v2ray.v2ray.utils.Utilities.getUserAssetsPath;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.CountDownTimer;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.Objects;

import com.example.null_end_v2ray.v2ray.interfaces.V2rayServicesListener;
import com.example.null_end_v2ray.v2ray.utils.AppConfigs;
import com.example.null_end_v2ray.v2ray.utils.Utilities;
import com.example.null_end_v2ray.v2ray.utils.V2rayConfig;
import com.example.null_end_v2ray.v2ray.services.V2rayProxyOnlyService;
import com.example.null_end_v2ray.v2ray.services.V2rayVPNService;
import com.example.null_end_v2ray.R;

import libv2ray.Libv2ray;
import libv2ray.V2RayPoint;
import libv2ray.V2RayVPNServiceSupportsSet;

public final class V2rayCoreManager {
    private volatile static V2rayCoreManager INSTANCE;
    public V2rayServicesListener v2rayServicesListener = null;
    private boolean isLibV2rayCoreInitialized = false;
    public AppConfigs.V2RAY_STATES V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;
    private CountDownTimer countDownTimer;
    private int seconds, minutes, hours;
    private long totalDownload, totalUpload, uploadSpeed, downloadSpeed;
    private String SERVICE_DURATION = "00:00:00";
    private NotificationManager mNotificationManager = null;

    private V2rayCoreManager() {}

    public static V2rayCoreManager getInstance() {
        if (INSTANCE == null) {
            synchronized (V2rayCoreManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new V2rayCoreManager();
                }
            }
        }
        return INSTANCE;
    }

    private void makeDurationTimer(final Context context, final boolean enable_traffic_statics) {
        countDownTimer = new CountDownTimer(7200, 1000) {
            @RequiresApi(api = Build.VERSION_CODES.M)
            public void onTick(long millisUntilFinished) {
                seconds++;
                if (seconds == 59) {
                    minutes++;
                    seconds = 0;
                }
                if (minutes == 59) {
                    minutes = 0;
                    hours++;
                }
                if (hours == 23) {
                    hours = 0;
                }
                if (enable_traffic_statics) {
                    downloadSpeed = v2RayPoint.queryStats("block", "downlink") + v2RayPoint.queryStats("proxy", "downlink");
                    uploadSpeed = v2RayPoint.queryStats("block", "uplink") + v2RayPoint.queryStats("proxy", "uplink");
                    totalDownload = totalDownload + downloadSpeed;
                    totalUpload = totalUpload + uploadSpeed;
                }
                SERVICE_DURATION = Utilities.convertIntToTwoDigit(hours) + ":" + Utilities.convertIntToTwoDigit(minutes) + ":" + Utilities.convertIntToTwoDigit(seconds);
                Intent connection_info_intent = new Intent("V2RAY_CONNECTION_INFO");
                connection_info_intent.putExtra("STATE", V2rayCoreManager.getInstance().V2RAY_STATE);
                connection_info_intent.putExtra("DURATION", SERVICE_DURATION);
                connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
                connection_info_intent.putExtra("DOWNLOAD_SPEED", downloadSpeed);
                connection_info_intent.putExtra("UPLOAD_TRAFFIC", totalUpload);
                connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", totalDownload);
                context.sendBroadcast(connection_info_intent);
            }

            public void onFinish() {
                countDownTimer.cancel();
                if (V2rayCoreManager.getInstance().isV2rayCoreRunning())
                    makeDurationTimer(context, enable_traffic_statics);
            }
        }.start();
    }


    public void setUpListener(Service targetService) {
        try {
            v2rayServicesListener = (V2rayServicesListener) targetService;
            Libv2ray.initV2Env(getUserAssetsPath(targetService.getApplicationContext()), "");
            isLibV2rayCoreInitialized = true;
            SERVICE_DURATION = "00:00:00";
            seconds = 0;
            minutes = 0;
            hours = 0;
            uploadSpeed = 0;
            downloadSpeed = 0;
            totalDownload = 0;
            totalUpload = 0;
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener => new initialize from " + v2rayServicesListener.getService().getClass().getSimpleName());
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "setUpListener failed => ", e);
            isLibV2rayCoreInitialized = false;
        }
    }

    public final V2RayPoint v2RayPoint = Libv2ray.newV2RayPoint(new V2RayVPNServiceSupportsSet() {
        @Override
        public long shutdown() {
            if (v2rayServicesListener == null) {
                Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed => can`t find initial service.");
                return -1;
            }
            try {
                v2rayServicesListener.stopService();
                v2rayServicesListener = null;
                return 0;
            } catch (Exception e) {
                Log.e(V2rayCoreManager.class.getSimpleName(), "shutdown failed =>", e);
                return -1;
            }
        }

        @Override
        public long prepare() {
            return 0;
        }

        @Override
        public boolean protect(long l) {
            if (v2rayServicesListener != null)
                return v2rayServicesListener.onProtect((int) l);
            return true;
        }

        @Override
        public long onEmitStatus(long l, String s) {
            return 0;
        }

        @Override
        public long setup(String s) {
            if (v2rayServicesListener != null) {
                try {
                    v2rayServicesListener.startService();
                } catch (Exception e) {
                    Log.e(V2rayCoreManager.class.getSimpleName(), "setup failed => ", e);
                    return -1;
                }
            }
            return 0;
        }
    }, Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1);


    public boolean startCore(final V2rayConfig v2rayConfig) {
        makeDurationTimer(v2rayServicesListener.getService().getApplicationContext(),
                v2rayConfig.ENABLE_TRAFFIC_STATICS);
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTING;
        if (!isLibV2rayCoreInitialized) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed => LibV2rayCore should be initialize before start.");
            return false;
        }
        if (isV2rayCoreRunning()) {
            stopCore();
        }
        try {
            // Libv2ray.testConfig(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
        } catch (Exception e) {
            sendDisconnectedBroadCast();
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed => v2ray json config not valid.");
            return false;
        }
        try {
            v2RayPoint.setConfigureFileContent(v2rayConfig.V2RAY_FULL_JSON_CONFIG);
            v2RayPoint.setDomainName(v2rayConfig.CONNECTED_V2RAY_SERVER_ADDRESS + ":" + v2rayConfig.CONNECTED_V2RAY_SERVER_PORT);
            v2RayPoint.runLoop(false);
            V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_CONNECTED;
            if (isV2rayCoreRunning()) {
                showNotification(v2rayConfig);
            }
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "startCore failed =>", e);
            return false;
        }
        return true;
    }

    public void stopCore() {
        try {
            if (isV2rayCoreRunning()) {
                v2RayPoint.stopLoop();
                v2rayServicesListener.stopService();
                Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore success => v2ray core stopped.");
            } else {
                Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore failed => v2ray core not running.");
            }
            sendDisconnectedBroadCast();
        } catch (Exception e) {
            Log.e(V2rayCoreManager.class.getSimpleName(), "stopCore failed =>", e);
        }
    }

    private void sendDisconnectedBroadCast() {
        V2RAY_STATE = AppConfigs.V2RAY_STATES.V2RAY_DISCONNECTED;
        SERVICE_DURATION = "00:00:00";
        seconds = 0;
        minutes = 0;
        hours = 0;
        uploadSpeed = 0;
        downloadSpeed = 0;
        if (v2rayServicesListener != null) {
            Intent connection_info_intent = new Intent("V2RAY_CONNECTION_INFO");
            connection_info_intent.putExtra("STATE", V2rayCoreManager.getInstance().V2RAY_STATE);
            connection_info_intent.putExtra("DURATION", SERVICE_DURATION);
            connection_info_intent.putExtra("UPLOAD_SPEED", uploadSpeed);
            connection_info_intent.putExtra("DOWNLOAD_SPEED", uploadSpeed);
            connection_info_intent.putExtra("UPLOAD_TRAFFIC", uploadSpeed);
            connection_info_intent.putExtra("DOWNLOAD_TRAFFIC", uploadSpeed);
            try {
                v2rayServicesListener.getService().getApplicationContext().sendBroadcast(connection_info_intent);
            } catch (Exception e) {
                //ignore
            }
        }
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }
//
//    private fun getNotificationManager(): NotificationManager? {
//        if (mNotificationManager == null) {
//            val service = serviceControl?.get()?.getService() ?: return null
//            mNotificationManager =
//                    service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        }
//        return mNotificationManager
//    }

    private NotificationManager getNotificationManager() {
        if (mNotificationManager == null) {
            try {
                mNotificationManager = (NotificationManager) v2rayServicesListener.getService().getSystemService(Context.NOTIFICATION_SERVICE);
            } catch (Exception e) {
                return null;
            }
        }
        return mNotificationManager;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private String createNotificationChannelID(final String Application_name) {
        String notification_channel_id = "DEV7_DEV_V_E_CH_ID";
        NotificationChannel notificationChannel = new NotificationChannel(
                notification_channel_id, Application_name + " Background Service", NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setLightColor(Color.BLUE);
        notificationChannel.setImportance(NotificationManager.IMPORTANCE_DEFAULT);
        Objects.requireNonNull(getNotificationManager()).createNotificationChannel(notificationChannel);
        return notification_channel_id;
    }

    private int judgeForNotificationFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        } else {
            return PendingIntent.FLAG_UPDATE_CURRENT;
        }
    }

    private void showNotification(final V2rayConfig v2rayConfig) {
        if (v2rayServicesListener == null) {
            return;
        }
        Intent launchIntent = v2rayServicesListener.getService().getPackageManager().
                getLaunchIntentForPackage(v2rayServicesListener.getService().getApplicationInfo().packageName);
        launchIntent.setAction("FROM_DISCONNECT_BTN");
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent notificationContentPendingIntent = PendingIntent.getActivity(
                v2rayServicesListener.getService(), 0, launchIntent, judgeForNotificationFlag());
        String notificationChannelID = "";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationChannelID = createNotificationChannelID(v2rayConfig.APPLICATION_NAME);
        }
    
        Intent stop_intent;
        if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.PROXY_ONLY) {
            stop_intent = new Intent(v2rayServicesListener.getService(), V2rayProxyOnlyService.class);
        } else if (AppConfigs.V2RAY_CONNECTION_MODE == AppConfigs.V2RAY_CONNECTION_MODES.VPN_TUN) {
            stop_intent = new Intent(v2rayServicesListener.getService(), V2rayVPNService.class);
        } else {
            return;
        }
        stop_intent.putExtra("COMMAND", AppConfigs.V2RAY_SERVICE_COMMANDS.STOP_SERVICE);

        PendingIntent pendingIntent = PendingIntent.getService(
            v2rayServicesListener.getService(),
            0,
            stop_intent,
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(v2rayServicesListener.getService(), notificationChannelID);
        mBuilder.setSmallIcon(v2rayConfig.APPLICATION_ICON)
                .setContentTitle(v2rayConfig.REMARK)
                // .setContentText("tap to open application")
                .addAction(0, "DISCONNECT", pendingIntent)
                .setSmallIcon(R.drawable.baseline_vpn_key_24);
                // .setContentIntent(notificationContentPendingIntent);
        v2rayServicesListener.getService().startForeground(1, mBuilder.build());
    }

    public boolean isV2rayCoreRunning() {
        if (v2RayPoint != null) {
            return v2RayPoint.getIsRunning();
        }
        return false;
    }

    public Long getConnectedV2rayServerDelay() {
        try {
            return v2RayPoint.measureDelay(AppConfigs.DELAY_URL);
        } catch (Exception e) {
            return -1L;
        }
    }

    public Long getV2rayServerDelay(final String config, final String url) {
        try {
            try {
                JSONObject config_json = new JSONObject(config);
                JSONObject new_routing_json = config_json.getJSONObject("routing");
                new_routing_json.remove("rules");
                config_json.remove("routing");
                config_json.put("routing", new_routing_json);
                return Libv2ray.measureOutboundDelay(config_json.toString(), url);
            } catch (Exception json_error) {
                Log.e("getV2rayServerDelay", json_error.toString());
                return Libv2ray.measureOutboundDelay(config, url);
            }
        } catch (Exception e) {
            Log.e("getV2rayServerDelayCore", e.toString());
            return -1L;
        }
    }

}

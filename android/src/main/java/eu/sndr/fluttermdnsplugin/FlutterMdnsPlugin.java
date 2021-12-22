package eu.sndr.fluttermdnsplugin;

import static android.content.ContentValues.TAG;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import eu.sndr.fluttermdnsplugin.handlers.DiscoveryRunningHandler;
import eu.sndr.fluttermdnsplugin.handlers.ServiceDiscoveredHandler;
import eu.sndr.fluttermdnsplugin.handlers.ServiceLostHandler;
import eu.sndr.fluttermdnsplugin.handlers.ServiceResolvedHandler;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * FlutterMdnsPlugin
 */
public class FlutterMdnsPlugin implements MethodCallHandler, FlutterPlugin {

    private final static String NAMESPACE = "eu.sndr.mdns";

    private NsdManager mNsdManager;
    private NsdManager.DiscoveryListener mDiscoveryListener;
    private ArrayList<NsdServiceInfo> mDiscoveredServices;
    private MethodChannel methodChannel;
    private Context applicationContext;
    EventChannel serviceDiscoveredChannel;
    EventChannel serviceResolved;
    EventChannel serviceLost;
    EventChannel discoveryRunning;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.applicationContext = flutterPluginBinding.getApplicationContext();
        methodChannel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_mdns_plugin");
        methodChannel.setMethodCallHandler(this);

        mDiscoveredServices = new ArrayList<>();
        System.out.println("We are beginning the search process");

        serviceDiscoveredChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), NAMESPACE + "/discovered");
        mDiscoveredHandler = new ServiceDiscoveredHandler();
        serviceDiscoveredChannel.setStreamHandler(mDiscoveredHandler);

        serviceResolved = new EventChannel(flutterPluginBinding.getBinaryMessenger(), NAMESPACE + "/resolved");
        mResolvedHandler = new ServiceResolvedHandler();
        serviceResolved.setStreamHandler(mResolvedHandler);

        serviceLost = new EventChannel(flutterPluginBinding.getBinaryMessenger(), NAMESPACE + "/lost");
        mLostHandler = new ServiceLostHandler();
        serviceLost.setStreamHandler(mLostHandler);

        discoveryRunning = new EventChannel(flutterPluginBinding.getBinaryMessenger(), NAMESPACE + "/running");
        mDiscoveryRunningHandler = new DiscoveryRunningHandler(applicationContext);
        discoveryRunning.setStreamHandler(mDiscoveryRunningHandler);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        applicationContext = null;
        methodChannel.setMethodCallHandler(null);
        methodChannel = null;
        serviceDiscoveredChannel.setStreamHandler(null);
        serviceDiscoveredChannel = null;
        discoveryRunning.setStreamHandler(null);
        discoveryRunning = null;
        serviceLost.setStreamHandler(null);
        serviceLost = null;
        serviceResolved.setStreamHandler(null);
        serviceResolved = null;

    }

    public FlutterMdnsPlugin() {

    }

    private DiscoveryRunningHandler mDiscoveryRunningHandler;
    private ServiceDiscoveredHandler mDiscoveredHandler;
    private ServiceResolvedHandler mResolvedHandler;
    private ServiceLostHandler mLostHandler;

    @Override
    public void onMethodCall(MethodCall call, @NonNull Result result) {

        switch (call.method) {
            case "startDiscovery":
                startDiscovery(call.argument("serviceType"));
                result.success(null);
                break;
            case "stopDiscovery":
                stopDiscovery();
                result.success(null);
                break;
            case "requestDiscoveredServices":
                for (NsdServiceInfo serviceInfo : mDiscoveredServices) {

                }
                break;
            default:
                result.notImplemented();
                break;
        }

    }

    @SuppressLint("NewApi")
    private void startDiscovery(String serviceName) {

        mNsdManager = (NsdManager) applicationContext.getSystemService(Context.NSD_SERVICE);

        mDiscoveryListener = new NsdManager.DiscoveryListener() {

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, String.format(Locale.US,
                        "Discovery failed to start on %s with error : %d", serviceType, errorCode));
                mDiscoveryRunningHandler.onDiscoveryStopped();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, String.format(Locale.US,
                        "Discovxery failed to stop on %s with error : %d", serviceType, errorCode));
                mDiscoveryRunningHandler.onDiscoveryStarted();
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Started discovery for : " + serviceType);
                mDiscoveryRunningHandler.onDiscoveryStarted();
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Stopped discovery for : " + serviceType);
                mDiscoveryRunningHandler.onDiscoveryStopped();
            }

            @Override
            public void onServiceFound(NsdServiceInfo nsdServiceInfo) {
                Log.d(TAG, "Found Service : " + nsdServiceInfo.toString());
                mDiscoveredServices.add(nsdServiceInfo);
                mDiscoveredHandler.onServiceDiscovered(ServiceToMap(nsdServiceInfo));

                mNsdManager.resolveService(nsdServiceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int errorCode) {
                        Log.d(TAG, "Failed to resolve service : " + nsdServiceInfo.toString());

                        switch (errorCode) {
                            case NsdManager.FAILURE_ALREADY_ACTIVE:
                                Log.e(TAG, "FAILURE_ALREADY_ACTIVE");
                                // Just try again...
                                onServiceFound(nsdServiceInfo);
                                break;
                            case NsdManager.FAILURE_INTERNAL_ERROR:
                                Log.e(TAG, "FAILURE_INTERNAL_ERROR");
                                break;
                            case NsdManager.FAILURE_MAX_LIMIT:
                                Log.e(TAG, "FAILURE_MAX_LIMIT");
                                // https://stackoverflow.com/questions/16736142/nsnetworkmanager-resolvelistener-messages-android
                                onServiceFound(nsdServiceInfo);
                                break;
                        }
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
                        mResolvedHandler.onServiceResolved(ServiceToMap(nsdServiceInfo));
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo nsdServiceInfo) {
                Log.d(TAG, "Lost Service : " + nsdServiceInfo.toString());
                mLostHandler.onServiceLost(ServiceToMap(nsdServiceInfo));
            }
        };

        mNsdManager.discoverServices(serviceName, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);

    }

    private void stopDiscovery() {

        if (mNsdManager != null && mDiscoveryListener != null) {
            mNsdManager.stopServiceDiscovery(mDiscoveryListener);
        }

    }

    /**
     * serviceToMap converts an NsdServiceInfo object into a map of relevant info
     * The map can be interpreted by the StandardMessageCodec of Flutter and makes sending data back and forth simpler.
     *
     * @param info The ServiceInfo to convert
     * @return The map that can be interpreted by Flutter and sent back on an EventChannel
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static Map<String, Object> ServiceToMap(NsdServiceInfo info) {
        Map<String, Object> map = new HashMap<>();

        map.put("attr", info.getAttributes() != null ? info.getAttributes() : Collections.emptyMap());

        map.put("name", info.getServiceName() != null ? info.getServiceName() : "");

        map.put("type", info.getServiceType() != null ? info.getServiceType() : "");

        map.put("hostName", info.getHost() != null ? info.getHost().getHostName() : "");

        map.put("address", info.getHost() != null ? info.getHost().getHostAddress() : "");

        map.put("port", info.getPort());

        return map;
    }

}

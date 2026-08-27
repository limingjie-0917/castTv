package com.github.serezhka.jap2lib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.util.Log;

import javax.jmdns.JmmDNS;
import javax.jmdns.ServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers airplay/airtunes service mdns
 */
public class AirPlayBonjour {

    private static final Logger log = LoggerFactory.getLogger(AirPlayBonjour.class);

    private static final String AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local.";
    private static final String AIRTUNES_SERVICE_TYPE = "_raop._tcp.local.";
    private static final String HDPT_SERVICE_TYPE = "_hdktp._tcp.local.";

    private final String serverName;
    private BonjourListener listener;

    /**
     * Callback for mDNS registration events. Used by AirPlayService to write
     * diagnostics logs so users can troubleshoot "iPhone screen mirroring cannot find this TV".
     */
    public interface BonjourListener {
        /** Called when both _airplay._tcp and _raop._tcp are successfully registered. */
        void onRegistered(String serverName, int airPlayPort, int airTunesPort, String networkInfo);
        /** Called when mDNS registration fails with an exception. */
        void onRegisterFailed(String reason, Throwable error);
    }

    private ServiceInfo airPlayService;
    private ServiceInfo airTunesService;
    private ServiceInfo dhkptService;

    public AirPlayBonjour(String serverName) {
        this.serverName = serverName;
    }

    public void setListener(BonjourListener listener) {
        this.listener = listener;
    }

    /** 获取真实 MAC 地址，替代硬编码的 01:02:03:04:05:06 */
    private static String getRealMacAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length == 6) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) sb.append(":");
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.warn("getRealMacAddress failed: {}", e.getMessage());
        }
        return "01:02:03:04:05:06";
    }

    /** 获取本机所有活跃网络接口的 IP 地址，用于诊断 */
    private static String getNetworkInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip != null && !ip.contains(":")) { // 过滤 IPv6
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(ni.getName()).append("=").append(ip);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("getNetworkInfo failed: {}", e.getMessage());
        }
        return sb.length() > 0 ? sb.toString() : "(none)";
    }

    public void start(int airPlayPort, int airTunesPort) throws Exception {
        // NOTE: the first argument to ServiceInfo.create() is the *service type*
        // (must be a valid, ASCII, RFC-compliant "_svc._tcp.local." string) and
        // the second is the *instance name* (may be UTF-8, e.g. "小新的TV").
        // Previously the device name was concatenated into the type which
        // produced a malformed type and fed invalid data into the mDNS stack.
        try {
            String realMac = getRealMacAddress();
            String netInfo = getNetworkInfo();

            // 使用 JmmDNS.Factory 单例 —— 不要 close 它！
            // close() 会破坏单例内部状态，导致后续 getInstance() 返回已关闭的实例
            JmmDNS mdns = JmmDNS.Factory.getInstance();

            airPlayService = ServiceInfo.create(AIRPLAY_SERVICE_TYPE,
                    serverName, airPlayPort, 0, 0, airPlayMDNSProps(realMac));
            mdns.registerService(airPlayService);
            log.info("{} ({}) service is registered on port {}", serverName, AIRPLAY_SERVICE_TYPE, airPlayPort);

            String airTunesServerName = realMac.replace(":", "").toLowerCase() + "@" + serverName;
            airTunesService = ServiceInfo.create(AIRTUNES_SERVICE_TYPE,
                    airTunesServerName, airTunesPort, 0, 0, airTunesMDNSProps(realMac));
            mdns.registerService(airTunesService);
            log.info("{} ({}) service is registered on port {}", airTunesServerName, AIRTUNES_SERVICE_TYPE, airTunesPort);

//            dhkptService = ServiceInfo.create(HDPT_SERVICE_TYPE,
//                    serverName, 56464, 0, 0, registerHdkpt(airPlayPort, airTunesPort));
//            mdns.registerService(dhkptService);

            // Notify listener: both services registered successfully
            if (listener != null) {
                listener.onRegistered(serverName, airPlayPort, airTunesPort, netInfo);
            }
        } catch (Exception e) {
            // Notify listener: mDNS registration failed (iPhone won't find this TV)
            if (listener != null) {
                listener.onRegisterFailed("mDNS register failed: " + e.getMessage(), e);
            }
            throw e;
        }
    }


    public Map<String, String> registerHdkpt(int airPlayPort, int airTunesPort) {
        HashMap<String, String> MDNSProps = new HashMap<>();
        MDNSProps.put("vertical", "0"); // vertical=0
        MDNSProps.put("aac", String.valueOf(airTunesPort));//TXT: aac=43573
        MDNSProps.put("cmd", String.valueOf(airTunesPort));//TXT: aac=43573
        MDNSProps.put("pcm", String.valueOf(airTunesPort));//TXT: aac=43573
        MDNSProps.put("code", "803797");//TXT: code=803797
        MDNSProps.put("h264", String.valueOf(airPlayPort));//TXT: h264=43572
        MDNSProps.put("hevc", "43578");//TXT: hevc=43578
        MDNSProps.put("name", serverName);//TXT: name=Kandao MT0822
        MDNSProps.put("type", "1");//TXT: type=1
        MDNSProps.put("alive", "43576");//TXT: alive=43576
        MDNSProps.put("discover", "1");//TXT: discover=1

        return MDNSProps;


    }

    public void stop() {
        // 使用 JmmDNS.Factory 单例，只 unregister 服务，不 close 实例
        JmmDNS mdns = JmmDNS.Factory.getInstance();
        if (airPlayService != null) {
            try {
                mdns.unregisterService(airPlayService);
                log.info("{} service is unregistered", airPlayService.getName());
            } catch (Exception e) {
                log.warn("unregister airplay service failed: {}", e.getMessage());
            }
            airPlayService = null;
        }
        if (airTunesService != null) {
            try {
                mdns.unregisterService(airTunesService);
                log.info("{} service is unregistered", airTunesService.getName());
            } catch (Exception e) {
                log.warn("unregister airtunes service failed: {}", e.getMessage());
            }
            airTunesService = null;
        }
        // 不调用 mdns.close()，保持 JmmDNS 单例活跃，避免下次 start 时返回已关闭的实例
    }

    private Map<String, String> airPlayMDNSProps(String deviceId) {
        HashMap<String, String> airPlayMDNSProps = new HashMap<>();
        airPlayMDNSProps.put("deviceid", deviceId);
        airPlayMDNSProps.put("features", "0x5A7FFFF7,0x1E");
        airPlayMDNSProps.put("srcvers", "220.68");
        airPlayMDNSProps.put("flags", "0x4");
        airPlayMDNSProps.put("vv", "2");
        airPlayMDNSProps.put("model", "AppleTV2,1");
        airPlayMDNSProps.put("rhd", "5.6.0.0");
        airPlayMDNSProps.put("pw", "false");
        airPlayMDNSProps.put("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7");
        airPlayMDNSProps.put("pi", "2e388006-13ba-4041-9a67-25dd4a43d536");
        return airPlayMDNSProps;
    }

    private Map<String, String> airTunesMDNSProps(String deviceId) {
        HashMap<String, String> airTunesMDNSProps = new HashMap<>();
        airTunesMDNSProps.put("ch", "2");
        airTunesMDNSProps.put("cn", "0,1,2,3");
        airTunesMDNSProps.put("da", "true");
        airTunesMDNSProps.put("et", "0,3,5");
        airTunesMDNSProps.put("vv", "2");
        airTunesMDNSProps.put("ft", "0x5A7FFFF7,0x1E");
        airTunesMDNSProps.put("am", "AppleTV2,1");
        airTunesMDNSProps.put("md", "0,1,2");
        airTunesMDNSProps.put("rhd", "5.6.0.0");
        airTunesMDNSProps.put("pw", "false");
        airTunesMDNSProps.put("sr", "44100");
        airTunesMDNSProps.put("ss", "16");
        airTunesMDNSProps.put("sv", "false");
        airTunesMDNSProps.put("tp", "UDP");
        airTunesMDNSProps.put("txtvers", "1");
        airTunesMDNSProps.put("sf", "0x4");
        airTunesMDNSProps.put("vs", "220.68");
        airTunesMDNSProps.put("vn", "65537");
        airTunesMDNSProps.put("pk", "b07727d6f6cd6e08b58ede525ec3cdeaa252ad9f683feb212ef8a205246554e7");
        return airTunesMDNSProps;
    }
}

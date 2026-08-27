package com.github.serezhka.jap2server;

import com.github.serezhka.jap2lib.AirPlayBonjour;
import com.github.serezhka.jap2server.internal.ControlServer;

public class AirPlayServer {

    private final AirPlayBonjour airPlayBonjour;
    private final AirplayDataConsumer airplayDataConsumer;
    private final ControlServer controlServer;

    private final String serverName;
    private final int airPlayPort;
    private final int airTunesPort;

    private volatile Thread controlThread;

    public AirPlayServer(String serverName, int airPlayPort, int airTunesPort,
                         AirplayDataConsumer airplayDataConsumer) {
        this.serverName = serverName;
        airPlayBonjour = new AirPlayBonjour(serverName);
        this.airPlayPort = airPlayPort;
        this.airTunesPort = airTunesPort;
        this.airplayDataConsumer = airplayDataConsumer;
        controlServer = new ControlServer(airPlayPort, airTunesPort, airplayDataConsumer);
    }

    /** Set Bonjour listener to receive mDNS registration events. */
    public void setBonjourListener(com.github.serezhka.jap2lib.AirPlayBonjour.BonjourListener listener) {
        airPlayBonjour.setListener(listener);
    }

    public void start() throws Exception {
        airPlayBonjour.start(airPlayPort, airTunesPort);
        Thread thread = new Thread(controlServer, "AirPlayControlServer");
        thread.setDaemon(true);
        controlThread = thread;
        thread.start();
    }

    public void stop() {
        // Order matters: unregister mDNS first (best effort), then tear the
        // Netty control server (threads + listen socket) fully down so a
        // subsequent start() does not leak threads/fds or fail to re-bind.
        try {
            airPlayBonjour.stop();
        } catch (Exception e) {
            // ignore – mDNS may not have registered
        }
        try {
            controlServer.stop();
        } catch (Exception e) {
            // ignore
        }
        Thread thread = controlThread;
        controlThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    // TODO On client connected / disconnected
}

package com.github.serezhka.jap2server.internal;

import com.github.serezhka.jap2server.AirplayDataConsumer;
import com.github.serezhka.jap2server.internal.handler.control.FairPlayHandler;
import com.github.serezhka.jap2server.internal.handler.control.HeartBeatHandler;
import com.github.serezhka.jap2server.internal.handler.control.PairingHandler;
import com.github.serezhka.jap2server.internal.handler.control.RTSPHandler;
import com.github.serezhka.jap2server.internal.handler.mirroring.MirroringHandler;
import com.github.serezhka.jap2server.internal.handler.session.SessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class ControlServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MirroringHandler.class);

    private final PairingHandler pairingHandler;
    private final FairPlayHandler fairPlayHandler;
    private final RTSPHandler rtspHandler;
    private final HeartBeatHandler heartBeatHandler;

    private final int airTunesPort;

    // Keep references so the server can be shut down cleanly. Without this the
    // Netty boss/worker threads and the bound listen socket leak on every
    // stop()/start() cycle (e.g. when the device name changes), eventually
    // exhausting threads/file-descriptors and getting the whole process killed
    // by the OS (native SIGABRT) a few seconds after launch.
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    public ControlServer(int airPlayPort, int airTunesPort, AirplayDataConsumer airplayDataConsumer) {
        this.airTunesPort = airTunesPort;
        SessionManager sessionManager = new SessionManager();
        pairingHandler = new PairingHandler(sessionManager);
        fairPlayHandler = new FairPlayHandler(sessionManager);
        rtspHandler = new RTSPHandler(airPlayPort, airTunesPort, sessionManager, airplayDataConsumer);
        heartBeatHandler = new HeartBeatHandler(sessionManager);
    }

    @Override
    public void run() {
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        try {
            serverBootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .localAddress(new InetSocketAddress(airTunesPort))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(final SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(
                                    new RtspDecoder(),
                                    new RtspEncoder(),
                                    new HttpObjectAggregator(64 * 1024),
                                    new LoggingHandler(LogLevel.DEBUG),
                                    pairingHandler,
                                    fairPlayHandler,
                                    rtspHandler,
                                    heartBeatHandler);
                        }
                    })
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            ChannelFuture channelFuture = serverBootstrap.bind().sync();
            serverChannel = channelFuture.channel();
            log.info("Control server listening on port: {}", airTunesPort);
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Bind failures (e.g. port already in use because a previous
            // instance was not fully torn down) must NOT propagate to the
            // default uncaught-exception handler / kill the process.
            log.warn("Control server terminated: {}", e.getMessage());
        } finally {
            log.info("Control server stopped");
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * Cleanly shut the control server down: close the listen socket and release
     * both Netty event-loop groups. Safe to call multiple times and from any
     * thread.
     */
    public void stop() {
        Channel channel = serverChannel;
        serverChannel = null;
        if (channel != null) {
            try {
                channel.close().syncUninterruptibly();
            } catch (Exception e) {
                log.warn("closing server channel failed: {}", e.getMessage());
            }
        }
        EventLoopGroup boss = bossGroup;
        bossGroup = null;
        if (boss != null) {
            boss.shutdownGracefully();
        }
        EventLoopGroup worker = workerGroup;
        workerGroup = null;
        if (worker != null) {
            worker.shutdownGracefully();
        }
    }
}

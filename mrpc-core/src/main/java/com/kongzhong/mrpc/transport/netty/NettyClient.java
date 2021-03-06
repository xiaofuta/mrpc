package com.kongzhong.mrpc.transport.netty;

import com.kongzhong.mrpc.client.LocalServiceNodeTable;
import com.kongzhong.mrpc.config.ClientConfig;
import com.kongzhong.mrpc.config.NettyConfig;
import com.kongzhong.mrpc.enums.TransportEnum;
import com.kongzhong.mrpc.exception.SystemException;
import com.kongzhong.mrpc.transport.http.HttpClientChannelInitializer;
import com.kongzhong.mrpc.transport.http.HttpClientHandler;
import com.kongzhong.mrpc.transport.tcp.TcpClientChannelInitializer;
import com.kongzhong.mrpc.transport.tcp.TcpClientHandler;
import com.kongzhong.mrpc.utils.HttpRequest;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.internal.SocketUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Netty Client
 *
 * @author biezhi
 *         24/06/2017
 */
@Slf4j
public class NettyClient {

    @Getter
    private SocketAddress serverAddress;

    @Getter
    private String address;

    @Getter
    private boolean isRunning = true;

    @Getter
    private LongAdder retryCount = new LongAdder();

    private NettyConfig nettyConfig;

    @Setter
    private TransportEnum transport = ClientConfig.me().getTransport();

    public NettyClient(NettyConfig nettyConfig, String address) {
        this.nettyConfig = nettyConfig;
        this.address = address;

        String host = address.split(":")[0];
        int port = Integer.valueOf(address.split(":")[1]);
        this.serverAddress = SocketUtils.socketAddress(host, port);

    }

    /**
     * 同步创建Channel
     *
     * @param eventLoopGroup
     * @return
     */
    public Channel syncCreateChannel(EventLoopGroup eventLoopGroup) {
        if (LocalServiceNodeTable.isAlive(this.getAddress())) {
            return null;
        }

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nettyConfig.getConnTimeout())
                .option(ChannelOption.SO_KEEPALIVE, true);

        if (this.transport.equals(TransportEnum.HTTP)) {
            bootstrap.handler(new HttpClientChannelInitializer(this));
        } else {
            bootstrap.handler(new TcpClientChannelInitializer(this));
        }
        // 和服务端建立连接,然后异步获取运行结果
        try {
            Channel channel = bootstrap.connect(serverAddress).sync().channel();
            while (!channel.isActive()) {
                TimeUnit.MILLISECONDS.sleep(100);
            }

            log.info("Connect {} success.", channel);

            boolean isHttp = ClientConfig.me().getTransport().equals(TransportEnum.HTTP);

            //和服务器连接成功后, 获取MessageSendHandler对象
            Class<? extends SimpleClientHandler> clientHandler = isHttp ? HttpClientHandler.class : TcpClientHandler.class;
            SimpleClientHandler handler = channel.pipeline().get(clientHandler);

            // 设置节点状态为存活状态
            LocalServiceNodeTable.setNodeAlive(handler);

            if (isHttp && ClientConfig.me().getPingInterval() > 0) {
                ScheduledFuture scheduledFuture = channel.eventLoop().scheduleAtFixedRate(() -> {
                    try {
                        if (!channel.isActive()) {
                            closeSchedule(channel);
                            return;
                        }
                        long start = System.currentTimeMillis();
                        int code = HttpRequest.get("http://" + this.getAddress() + "/status")
                                .connectTimeout(10_000)
                                .readTimeout(5000)
                                .code();
                        if (code == 200) {
                            log.info("Rpc send ping for {} after 0ms", channel, (System.currentTimeMillis() - start));
                        }
                    } catch (Exception e) {
                        log.warn("Rpc send ping error: {}", e.getMessage());
                    }
                }, 0, ClientConfig.me().getPingInterval(), TimeUnit.MILLISECONDS);
                scheduledFutureMap.put(channel, scheduledFuture);
            }
            return channel;
        } catch (Exception e) {
            throw new SystemException("Sync create bootstrap error", e);
        }
    }

    public Bootstrap asyncCreateBootstrap(EventLoopGroup eventLoopGroup) {
        if (LocalServiceNodeTable.isAlive(this.getAddress())) {
            return null;
        }

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nettyConfig.getConnTimeout())
                .option(ChannelOption.SO_KEEPALIVE, true);

        if (this.transport.equals(TransportEnum.HTTP)) {
            bootstrap.handler(new HttpClientChannelInitializer(this));
        } else {
            bootstrap.handler(new TcpClientChannelInitializer(this));
        }
        // 和服务端建立连接,然后异步获取运行结果
        ChannelFuture channelFuture = bootstrap.connect(serverAddress);
        // 给结果绑定 Listener,
        channelFuture.addListener(new ConnectionListener(this));
        return bootstrap;
    }

    private static final Map<Channel, ScheduledFuture> scheduledFutureMap = new HashMap<>();

    private void closeSchedule(Channel channel) {
        scheduledFutureMap.get(channel).cancel(true);
    }

    /**
     * 重置重试次数
     */
    public void resetRetryCount() {
        retryCount = new LongAdder();
    }

    public void shutdown() {
        isRunning = false;
    }

}

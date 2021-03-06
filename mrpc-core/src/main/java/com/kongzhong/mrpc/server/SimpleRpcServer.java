package com.kongzhong.mrpc.server;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.*;
import com.kongzhong.mrpc.Const;
import com.kongzhong.mrpc.common.thread.RpcThreadPool;
import com.kongzhong.mrpc.config.AdminConfig;
import com.kongzhong.mrpc.config.NettyConfig;
import com.kongzhong.mrpc.config.ServerConfig;
import com.kongzhong.mrpc.enums.EventType;
import com.kongzhong.mrpc.enums.NodeAliveStateEnum;
import com.kongzhong.mrpc.enums.RegistryEnum;
import com.kongzhong.mrpc.enums.TransportEnum;
import com.kongzhong.mrpc.event.EventManager;
import com.kongzhong.mrpc.exception.InitializeException;
import com.kongzhong.mrpc.exception.RpcException;
import com.kongzhong.mrpc.exception.SystemException;
import com.kongzhong.mrpc.model.*;
import com.kongzhong.mrpc.registry.DefaultRegistry;
import com.kongzhong.mrpc.registry.ServiceRegistry;
import com.kongzhong.mrpc.serialize.RpcSerialize;
import com.kongzhong.mrpc.serialize.jackson.JacksonSerialize;
import com.kongzhong.mrpc.transport.TransferSelector;
import com.kongzhong.mrpc.utils.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.kongzhong.mrpc.Const.HEADER_REQUEST_ID;

/**
 * 抽象服务端请求处理器
 *
 * @author biezhi
 *         2017/4/19
 */
@Slf4j
@NoArgsConstructor
@ToString(exclude = {"rpcMapping", "transferSelector"})
public abstract class SimpleRpcServer {

    /**
     * RPC服务映射
     */
    protected RpcMapping rpcMapping = RpcMapping.me();

    /**
     * 是否使用了注册中心
     */
    protected boolean usedRegistry;

    /**
     * 注册中心列表 [注册中心名->注册中心实现]
     */
    protected Map<String, ServiceRegistry> serviceRegistryMap = Maps.newHashMap();

    /**
     * 服务端拦截器，多个用逗号相隔，顺序拦截
     */
    @Getter
    @Setter
    protected String interceptors;

    /**
     * rpc服务地址
     */
    @Getter
    @Setter
    protected String address;

    /**
     * 弹性ip地址，不清楚可不填
     */
    @Getter
    @Setter
    protected String elasticIp;

    /**
     * 业务线程池前缀
     */
    @Getter
    @Setter
    protected String poolName = "mrpc-server";

    /**
     * 序列化类型，默认protostuff
     */
    @Getter
    @Setter
    protected String serialize;

    /**
     * 传输协议，默认tcp协议
     */
    @Getter
    @Setter
    protected String transport;

    /**
     * appId
     */
    @Getter
    @Setter
    protected String appId;

    /**
     * 是否是测试环境，如果 "true" 则在启动后不会挂起程序
     */
    @Getter
    @Setter
    protected String test;

    /**
     * 传输协议选择器
     */
    protected TransferSelector transferSelector;

    /**
     * netty服务端配置
     */
    @Getter
    @Setter
    protected NettyConfig nettyConfig;

    /**
     * 后台配置
     */
    @Getter
    @Setter
    protected AdminConfig adminConfig;

    /**
     * 服务端处理线程池
     */
    protected static ListeningExecutorService LISTENING_EXECUTOR_SERVICE;

    private ScheduledFuture adminSchedule;

    /**
     * 启动RPC服务端
     */
    protected void startServer() {
        this.initConfig();
        this.bindRpcServer();
    }

    private void initConfig() {
        if (null == nettyConfig) {
            nettyConfig = new NettyConfig(128, true);
        }

        if (null == transport) transport = "tcp";
        if (null == serialize) serialize = "kyro";

        if (CollectionUtils.isNotEmpty(serviceRegistryMap)) {
            usedRegistry = true;
        }

        RpcSerialize rpcSerialize = null;
        if (serialize.equalsIgnoreCase("kyro")) {
            rpcSerialize = ReflectUtils.newInstance("com.kongzhong.mrpc.serialize.KyroSerialize", RpcSerialize.class);
        }
        if (serialize.equalsIgnoreCase("protostuff")) {
            rpcSerialize = ReflectUtils.newInstance("com.kongzhong.mrpc.serialize.ProtostuffSerialize", RpcSerialize.class);
        }

        if (null == rpcSerialize) {
            throw new InitializeException("RPC server serialize is null.");
        }

        transferSelector = new TransferSelector(rpcSerialize);
        LISTENING_EXECUTOR_SERVICE = MoreExecutors.listeningDecorator((ThreadPoolExecutor) RpcThreadPool.getExecutor(nettyConfig.getBusinessThreadPoolSize(), -1));
    }

    private void bindRpcServer() {

        // 服务启动时
        EventManager.me().fireEvent(EventType.SERVER_STARTING, RpcContext.get());

//        ThreadFactory threadRpcFactory = new NamedThreadFactory(poolName);
//        int parallel = Runtime.getRuntime().availableProcessors() * 2;

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss, worker).channel(NioServerSocketChannel.class)
                    .childHandler(transferSelector.getServerChannelHandler(transport))
                    .option(ChannelOption.SO_BACKLOG, nettyConfig.getBacklog())
                    .childOption(ChannelOption.SO_KEEPALIVE, nettyConfig.isKeepalive())
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(nettyConfig.getLowWaterMark(), nettyConfig.getHighWaterMark()));

            String[] ipAddr = address.split(":");
            if (null == ipAddr) {
                throw new SystemException("RPC server bind address error, please check your server address and port.");
            }

            String host = null;
            int port = -1;

            if (ipAddr.length == 1) {
                host = NetUtils.getLocalAddress().getHostAddress();
                port = Integer.valueOf(ipAddr[0]);
                this.address = host + ':' + port;
            }

            if (ipAddr.length == 2) {
                host = ipAddr[0];
                port = Integer.parseInt(ipAddr[1]);
            }

            //获取服务器IP地址和端口
            ServerConfig.me().setElasticIp(elasticIp);
            ChannelFuture future = bootstrap.bind(host, port).sync();

            //注册服务
            rpcMapping.getServiceBeanMap().values().forEach(serviceBean -> {

                String appId = this.getAppId(serviceBean);
                String address = this.getBindAddress(serviceBean);
                String elasticIp = this.getRegisterElasticIp(serviceBean);
                boolean usedRegistry = this.usedRegistry(serviceBean);
                String serviceName = serviceBean.getServiceName();

                if (usedRegistry) {
                    // 查找该服务的注册中心
                    ServiceRegistry serviceRegistry = this.getRegistry(serviceBean);
                    try {
                        serviceBean.setRegistry(this.getRegistryName(serviceBean));
                        serviceBean.setAppId(appId);
                        serviceBean.setAddress(address);
                        serviceBean.setElasticIp(elasticIp);
                        serviceRegistry.register(serviceBean);

                        ServiceStatusTable.me().createServiceStatus(serviceBean);
                    } catch (RpcException e) {
                        log.error("Service register error", e);
                    }
                }
                if (StringUtils.isNotEmpty(elasticIp)) {
                    log.info("Register => [{}] - [{}]/[{}]", serviceName, address, elasticIp);
                } else {
                    log.info("Register => [{}] - [{}]", serviceName, address);
                }
                // 服务注册后
                EventManager.me().fireEvent(EventType.SERVER_SERVICE_REGISTER, RpcContext.get());
            });

            if (this.usedRegistry) {
                this.listenDestroy();
            }

            log.info("Publish services finished, mrpc version [{}]", Const.VERSION);

            // 服务启动后
            EventManager.me().fireEvent(EventType.SERVER_STARTED, RpcContext.get());

            this.channelSync(future);

        } catch (Exception e) {
            log.error("RPC server start error", e);
        } finally {
            worker.shutdownGracefully();
            boss.shutdownGracefully();
        }
    }

    /**
     * 后台监听
     *
     * @param future
     * @throws InterruptedException
     */
    private void channelSync(ChannelFuture future) throws InterruptedException {

        if (null != adminConfig && adminConfig.isEnabled()) {

            adminSchedule = future.channel().eventLoop().scheduleAtFixedRate(() -> {
                String url = adminConfig.getUrl() + "/api/service";
                ServiceNodePayload serviceNodePayload = ServiceNodePayload.builder()
                        .address(this.address)
                        .appId(this.appId)
                        .availAble(NodeAliveStateEnum.ALIVE)
                        .transport(TransportEnum.valueOf(this.transport.toUpperCase()))
                        .services(ServiceStatusTable.me().getServiceStatus())
                        .build();

                String body = JacksonSerialize.toJSONString(serviceNodePayload);
                log.debug("Request URL\t: {}", url);
                log.debug("Send body\t\t: {}", body);

                try {
                    int code = HttpRequest.post(url)
                            .contentType("application/json;charset=utf-8")
                            .connectTimeout(10_000)
                            .readTimeout(5000)
                            .basic(adminConfig.getUsername(), adminConfig.getPassword())
                            .send(body).code();

                    log.debug("Response code: {}", code);
                } catch (HttpRequest.HttpRequestException e) {
                    log.debug("连接失败");
                } catch (Exception e) {
                    log.error("Send error", e);
                    cancelAdminSchedule(true);
                }
            }, 100, adminConfig.getPeriod(), TimeUnit.MILLISECONDS);
        }

        if ("true".equals(this.test)) {
            new Thread(() -> {
                try {
                    future.channel().closeFuture().sync();
                } catch (Exception e) {
                    log.error("", e);
                }
            }).start();
        } else {
            future.channel().closeFuture().sync();
        }
    }

    private void cancelAdminSchedule(boolean mayInterruptIfRunning) {
        adminSchedule.cancel(mayInterruptIfRunning);
    }

    /**
     * 返回引用是否使用注册中心
     *
     * @param serviceBean
     * @return
     */
    protected boolean usedRegistry(ServiceBean serviceBean) {
        if (StringUtils.isNotEmpty(serviceBean.getRegistry())) {
            return true;
        }
        if (serviceRegistryMap.containsKey("default")) {
            return true;
        }
        return false;
    }

    protected String getAppId(ServiceBean serviceBean) {
        String appId = this.appId;
        if (StringUtils.isNotEmpty(serviceBean.getAppId())) {
            appId = serviceBean.getAppId();
        }
        return appId;
    }

    /**
     * 获取服务暴露的地址 ip:port
     *
     * @param serviceBean
     * @return
     */
    protected String getBindAddress(ServiceBean serviceBean) {
        String address = this.address;
        if (StringUtils.isNotEmpty(serviceBean.getAddress())) {
            address = serviceBean.getAddress();
        }
        return address;
    }

    protected String getRegisterElasticIp(ServiceBean serviceBean) {
        String elasticIp = this.elasticIp;
        if (StringUtils.isNotEmpty(serviceBean.getElasticIp())) {
            elasticIp = serviceBean.getElasticIp();
        }
        return elasticIp;
    }

    /**
     * 获取服务使用的注册中心
     *
     * @param serviceBean
     * @return
     */
    protected ServiceRegistry getRegistry(ServiceBean serviceBean) {
        return serviceRegistryMap.get(getRegistryName(serviceBean));
    }

    protected String getRegistryName(ServiceBean serviceBean) {
        return StringUtils.isNotEmpty(serviceBean.getRegistry()) ? serviceBean.getRegistry() : "default";
    }

    /**
     * 提交任务,异步获取结果.
     *
     * @param task
     * @param ctx
     * @param request
     * @param response
     */
    public static void submit(Callable<Boolean> task, final ChannelHandlerContext ctx, final RpcRequest request, final RpcResponse response) {

        //提交任务, 异步获取结果
        ListenableFuture<Boolean> listenableFuture = LISTENING_EXECUTOR_SERVICE.submit(task);

        //注册回调函数, 在task执行完之后 异步调用回调函数
        Futures.addCallback(listenableFuture, new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                //为返回msg回客户端添加一个监听器,当消息成功发送回客户端时被异步调用.
                ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
                    /**
                     * 服务端回显 request已经处理完毕
                     * @param channelFuture
                     * @throws Exception
                     */
                    public void operationComplete(ChannelFuture channelFuture) throws Exception {
                        log.debug("Server execute [{}] success.", request.getRequestId());
                    }
                });
            }

            @Override
            public void onFailure(Throwable t) {
//                log.error("", t);
            }
        }, LISTENING_EXECUTOR_SERVICE);
    }

    public static void submit(Callable<FullHttpResponse> task, final ChannelHandlerContext ctx) {
        //提交任务, 异步获取结果
        ListenableFuture<FullHttpResponse> listenableFuture = LISTENING_EXECUTOR_SERVICE.submit(task);
        //注册回调函数, 在task执行完之后 异步调用回调函数
        Futures.addCallback(listenableFuture, new FutureCallback<FullHttpResponse>() {
            @Override
            public void onSuccess(FullHttpResponse response) {
                // 服务端响应前
                EventManager.me().fireEvent(EventType.SERVER_PRE_RESPONSE, RpcContext.get());

                //为返回msg回客户端添加一个监听器,当消息成功发送回客户端时被异步调用.
                ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
                    /**
                     * 服务端回显 request已经处理完毕
                     * @param channelFuture
                     * @throws Exception
                     */
                    @Override
                    public void operationComplete(ChannelFuture channelFuture) throws Exception {
                        log.debug("Server execute [{}] success.", response.headers().get(HEADER_REQUEST_ID));
                    }

                });
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("", t);
            }
        }, LISTENING_EXECUTOR_SERVICE);
    }

    /**
     * 将map转换为注册中心实现
     *
     * @param map
     * @return
     */
    protected ServiceRegistry mapToRegistry(Map<String, String> map) {
        String type = map.get("type");
        if (RegistryEnum.DEFAULT.getName().equals(type)) {
            ServiceRegistry serviceRegistry = new DefaultRegistry();
            return serviceRegistry;
        }
        // Zookeeper注册中心
        if (RegistryEnum.ZOOKEEPER.getName().equals(type)) {
            String zkAddr = map.get("address");
            if (StringUtils.isEmpty(zkAddr)) {
                throw new SystemException("Zookeeper connect address not is empty");
            }
            log.info("RPC server connect zookeeper address: {}", zkAddr);
            return this.getZookeeperServiceRegistry(zkAddr);
        }
        return null;
    }

    protected ServiceRegistry getZookeeperServiceRegistry(String zkAddr) {
        try {
            Object zookeeperServiceRegistry = Class.forName("com.kongzhong.mrpc.registry.ZookeeperServiceRegistry").getConstructor(String.class).newInstance(zkAddr);
            ServiceRegistry serviceRegistry = (ServiceRegistry) zookeeperServiceRegistry;
            return serviceRegistry;
        } catch (Exception e) {
            log.error("", e);
        }
        return null;
    }

    /**
     * 销毁资源,卸载服务
     */
    protected void listenDestroy() {
        log.debug("RPC server backend listen destroy");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {

            rpcMapping.getServiceBeanMap().values().forEach(serviceBean -> {
                String serviceName = serviceBean.getServiceName();
                ServiceRegistry serviceRegistry = getRegistry(serviceBean);
                try {
                    serviceRegistry.unRegister(serviceBean);
                    log.debug("Unregister service => [{}]", serviceName);
                } catch (Exception e) {
                    log.error("Unregister service error", e);
                }
            });
        }));
    }
}
package com.kongzhong.mrpc.client.cluster.loadblance;

import com.kongzhong.mrpc.client.Connections;
import com.kongzhong.mrpc.client.LocalServiceNodeTable;
import com.kongzhong.mrpc.client.cluster.LoadBalance;
import com.kongzhong.mrpc.enums.LbStrategyEnum;
import com.kongzhong.mrpc.exception.RpcException;
import com.kongzhong.mrpc.model.ClientBean;
import com.kongzhong.mrpc.registry.ServiceDiscovery;
import com.kongzhong.mrpc.transport.netty.SimpleClientHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

/**
 * 软负载简单实现
 * <p>
 * Created by biezhi on 2016/12/30.
 */
@Slf4j
public class SimpleLoadBalance implements LoadBalance {

    private Random random = new Random();

    private LbStrategyEnum lbStrategy;

    /**
     * 性能优于AtomicInteger（JDK8出现）
     */
    private LongAdder posLong = new LongAdder();

    public SimpleLoadBalance(LbStrategyEnum lbStrategyEnum) {
        this.lbStrategy = lbStrategyEnum;
    }

    private LongAdder immediatelyDiscoverCount = new LongAdder();

    public static Map<String, ServiceDiscovery> serviceDiscoveryMap;

    @Override
    public SimpleClientHandler next(String serviceName) throws Exception {
        List<SimpleClientHandler> handlers = Connections.me().getHandlers(serviceName);
        if (handlers.size() == 1) {
            immediatelyDiscoverCount = new LongAdder();
            return handlers.get(0);
        }
        if (handlers.size() == 0) {

            if (immediatelyDiscoverCount.intValue() < 3) {

                log.warn("Service [{}] not found, begin immediately discovery.", serviceName);

                // 马上服务发现
                this.immediatelyDiscovery(serviceName);
                immediatelyDiscoverCount.add(1);
                return this.next(serviceName);
            }

            log.info("Local service mappings: {}", LocalServiceNodeTable.SERVICE_MAPPINGS);
            throw new RpcException("Service [" + serviceName + "] not found.");
        }
        if (lbStrategy == LbStrategyEnum.ROUND) {
            return this.round(handlers);
        }
        if (lbStrategy == LbStrategyEnum.RANDOM) {
            return this.random(handlers);
        }
        if (lbStrategy == LbStrategyEnum.LAST) {
            return this.last(handlers);
        }
        return null;
    }

    private void immediatelyDiscovery(String serviceName) {
        ClientBean clientBean = new ClientBean();
        clientBean.setServiceName(serviceName);
        try {
            ServiceDiscovery serviceDiscovery = serviceDiscoveryMap.get("default");
            serviceDiscovery.discover(clientBean);
        } catch (Exception e) {
            log.error("Service discovery error", e);
        }
    }

    /**
     * poll load connection
     *
     * @param connections
     * @return
     */
    private SimpleClientHandler round(List<SimpleClientHandler> connections) {
        int pos = posLong.intValue();
        if (pos >= connections.size()) {
            posLong = new LongAdder();
            pos = posLong.intValue();
        }
        SimpleClientHandler connection = connections.get(pos);
        posLong.add(1);
        return connection;
    }

    /**
     * random load connection
     *
     * @param connections
     * @return
     */
    private SimpleClientHandler random(List<SimpleClientHandler> connections) {
        int randomPos = 0;
        int max = connections.size();
        if (max != 1) {
            randomPos = random.nextInt(max);
        }
        return connections.get(randomPos);
    }

    /**
     * last load connection
     *
     * @param connections
     * @return
     */
    private SimpleClientHandler last(List<SimpleClientHandler> connections) {
        return connections.get(connections.size() - 1);
    }

}
/**
 * Copyright (C) 2010-2013 Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.rocketmq.client.impl.factory;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;

import com.alibaba.rocketmq.client.ClientConfig;
import com.alibaba.rocketmq.client.admin.MQAdminExtInner;
import com.alibaba.rocketmq.client.exception.MQBrokerException;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.client.impl.ClientRemotingProcessor;
import com.alibaba.rocketmq.client.impl.FindBrokerResult;
import com.alibaba.rocketmq.client.impl.MQAdminImpl;
import com.alibaba.rocketmq.client.impl.MQClientAPIImpl;
import com.alibaba.rocketmq.client.impl.MQClientManager;
import com.alibaba.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import com.alibaba.rocketmq.client.impl.consumer.MQConsumerInner;
import com.alibaba.rocketmq.client.impl.consumer.PullMessageService;
import com.alibaba.rocketmq.client.impl.consumer.RebalanceService;
import com.alibaba.rocketmq.client.impl.producer.DefaultMQProducerImpl;
import com.alibaba.rocketmq.client.impl.producer.MQProducerInner;
import com.alibaba.rocketmq.client.impl.producer.TopicPublishInfo;
import com.alibaba.rocketmq.client.log.ClientLogger;
import com.alibaba.rocketmq.client.producer.DefaultMQProducer;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.ServiceState;
import com.alibaba.rocketmq.common.constant.PermName;
import com.alibaba.rocketmq.common.help.FAQUrl;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.common.protocol.heartbeat.ConsumerData;
import com.alibaba.rocketmq.common.protocol.heartbeat.HeartbeatData;
import com.alibaba.rocketmq.common.protocol.heartbeat.ProducerData;
import com.alibaba.rocketmq.common.protocol.heartbeat.SubscriptionData;
import com.alibaba.rocketmq.common.protocol.route.BrokerData;
import com.alibaba.rocketmq.common.protocol.route.QueueData;
import com.alibaba.rocketmq.common.protocol.route.TopicRouteData;
import com.alibaba.rocketmq.remoting.exception.RemotingException;
import com.alibaba.rocketmq.remoting.netty.NettyClientConfig;


/**
 * 客户端Factory类，用来管理Producer与Consumer
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-6-15
 */
public class MQClientFactory {
    private ServiceState serviceState = ServiceState.CREATE_JUST;
    private final Logger log = ClientLogger.getLog();
    private final ClientConfig clientConfig;
    private final int factoryIndex;
    private final String clientId;
    private final long bootTimestamp = System.currentTimeMillis();

    // Producer对象
    private final ConcurrentHashMap<String/* group */, MQProducerInner> producerTable =
            new ConcurrentHashMap<String, MQProducerInner>();
    // Consumer对象
    private final ConcurrentHashMap<String/* group */, MQConsumerInner> consumerTable =
            new ConcurrentHashMap<String, MQConsumerInner>();
    // AdminExt对象
    private final ConcurrentHashMap<String/* group */, MQAdminExtInner> adminExtTable =
            new ConcurrentHashMap<String, MQAdminExtInner>();

    // Netty客户端配置
    private final NettyClientConfig nettyClientConfig;
    // RPC调用的封装类
    private final MQClientAPIImpl mQClientAPIImpl;
    private final MQAdminImpl mQAdminImpl;

    // 存储从Name Server拿到的Topic路由信息
    private final ConcurrentHashMap<String/* Topic */, TopicRouteData> topicRouteTable =
            new ConcurrentHashMap<String, TopicRouteData>();
    // 调用Name Server获取Topic路由信息时，加锁
    private final Lock lockNamesrv = new ReentrantLock();
    private final static long LockTimeoutMillis = 3000;

    // 心跳与注销动作加锁
    private final Lock lockHeartbeat = new ReentrantLock();

    // 存储Broker Name 与Broker Address的对应关系
    private final ConcurrentHashMap<String/* Broker Name */, HashMap<Long/* brokerId */, String/* address */>> brokerAddrTable =
            new ConcurrentHashMap<String, HashMap<Long, String>>();

    // 定时线程
    private final ScheduledExecutorService scheduledExecutorService = Executors
        .newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "MQClientFactoryScheduledThread");
            }
        });

    // 处理服务器主动发来的请求
    private final ClientRemotingProcessor clientRemotingProcessor;

    // 监听一个UDP端口，用来防止同一个Factory启动多份（有可能分布在多个JVM中）
    private DatagramSocket datagramSocket;

    // 拉消息服务
    private final PullMessageService pullMessageService;

    // Rebalance服务
    private final RebalanceService rebalanceService;

    // 内置Producer对象
    private final DefaultMQProducer defaultMQProducer;


    public MQClientFactory(ClientConfig clientConfig, int factoryIndex, String clientId) {
        this.clientConfig = clientConfig;
        this.factoryIndex = factoryIndex;
        this.nettyClientConfig = new NettyClientConfig();
        this.nettyClientConfig.setClientCallbackExecutorThreads(clientConfig
            .getClientCallbackExecutorThreads());
        this.clientRemotingProcessor = new ClientRemotingProcessor(this);
        this.mQClientAPIImpl = new MQClientAPIImpl(this.nettyClientConfig, this.clientRemotingProcessor);

        if (this.clientConfig.getNamesrvAddr() != null) {
            this.mQClientAPIImpl.updateNameServerAddressList(this.clientConfig.getNamesrvAddr());
            log.info("user specfied name server address: {}", this.clientConfig.getNamesrvAddr());
        }

        this.clientId = clientId;

        this.mQAdminImpl = new MQAdminImpl(this);

        this.pullMessageService = new PullMessageService(this);

        this.rebalanceService = new RebalanceService(this);

        this.defaultMQProducer = new DefaultMQProducer(MixAll.CLIENT_INNER_PRODUCER_GROUP);
        this.defaultMQProducer.resetClientConfig(clientConfig);

        log.info("created a new client fatory, FactoryIndex: {} ClinetID: {} {}",//
            this.factoryIndex, //
            this.clientId, //
            this.clientConfig);
    }


    private void makesureInstanceNameIsOnly(final String instanceName) throws MQClientException {
        int udpPort = 33333;

        int value = instanceName.hashCode();
        if (value < 0) {
            value = Math.abs(value);
        }

        udpPort += value % 10000;

        try {
            this.datagramSocket = new DatagramSocket(udpPort);
        }
        catch (SocketException e) {
            throw new MQClientException("instance name is a duplicate one[" + udpPort
                    + "], please set a new name"
                    + FAQUrl.suggestTodo(FAQUrl.CLIENT_INSTACNCE_NAME_DUPLICATE_URL), e);
        }
    }


    private void startScheduledTask() {
        // 定时获取Name Server地址
        if (null == this.clientConfig.getNamesrvAddr()) {
            this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

                @Override
                public void run() {
                    try {
                        MQClientFactory.this.mQClientAPIImpl.fetchNameServerAddr();
                    }
                    catch (Exception e) {
                        log.error("ScheduledTask fetchNameServerAddr exception", e);
                    }
                }
            }, 1000 * 10, 1000 * 60 * 2, TimeUnit.MILLISECONDS);
        }

        // 定时从Name Server获取Topic路由信息
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    MQClientFactory.this.updateTopicRouteInfoFromNameServer();
                }
                catch (Exception e) {
                    log.error("ScheduledTask updateTopicRouteInfoFromNameServer exception", e);
                }
            }
        }, 10, this.clientConfig.getPollNameServerInteval(), TimeUnit.MILLISECONDS);

        // 向所有Broker发送心跳信息（包含订阅关系等）
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    MQClientFactory.this.sendHeartbeatToAllBrokerWithLock();
                }
                catch (Exception e) {
                    log.error("ScheduledTask sendHeartbeatToAllBroker exception", e);
                }
            }
        }, 1000, this.clientConfig.getHeartbeatBrokerInterval(), TimeUnit.MILLISECONDS);

        // 定时持久化Consumer消费进度（广播存储到本地，集群存储到Broker）
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    MQClientFactory.this.persistAllConsumerOffset();
                }
                catch (Exception e) {
                    log.error("ScheduledTask persistAllConsumerOffset exception", e);
                }
            }
        }, 1000 * 10, this.clientConfig.getPersistConsumerOffsetInterval(), TimeUnit.MILLISECONDS);

        // 统计信息打点
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    MQClientFactory.this.recordSnapshotPeriodically();
                }
                catch (Exception e) {
                    log.error("ScheduledTask uploadConsumerOffsets exception", e);
                }
            }
        }, 1000 * 10, 1000, TimeUnit.MILLISECONDS);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    MQClientFactory.this.logStatsPeriodically();
                }
                catch (Exception e) {
                    log.error("ScheduledTask uploadConsumerOffsets exception", e);
                }
            }
        }, 1000 * 10, 1000 * 60, TimeUnit.MILLISECONDS);
    }


    public void start() throws MQClientException {
        synchronized (this) {
            switch (this.serviceState) {
            case CREATE_JUST:
                this.makesureInstanceNameIsOnly(this.clientConfig.getInstanceName());

                this.serviceState = ServiceState.RUNNING;
                if (null == this.clientConfig.getNamesrvAddr()) {
                    this.clientConfig.setNamesrvAddr(this.mQClientAPIImpl.fetchNameServerAddr());
                }

                this.mQClientAPIImpl.start();
                this.startScheduledTask();
                this.pullMessageService.start();
                this.rebalanceService.start();

                this.defaultMQProducer.getDefaultMQProducerImpl().start(false);
                log.info("the client factory [{}] start OK", this.clientId);
                break;
            case RUNNING:
                break;
            case SHUTDOWN_ALREADY:
                break;
            default:
                break;
            }
        }
    }


    public void shutdown() {
        // Consumer
        if (!this.consumerTable.isEmpty())
            return;

        // AdminExt
        if (!this.adminExtTable.isEmpty())
            return;

        // Producer
        if (this.producerTable.size() > 1)
            return;

        synchronized (this) {
            switch (this.serviceState) {
            case CREATE_JUST:
                break;
            case RUNNING:
                this.defaultMQProducer.getDefaultMQProducerImpl().shutdown(false);

                this.serviceState = ServiceState.SHUTDOWN_ALREADY;
                this.pullMessageService.shutdown(true);
                this.scheduledExecutorService.shutdown();
                this.mQClientAPIImpl.shutdown();
                this.rebalanceService.shutdown();

                if (this.datagramSocket != null) {
                    this.datagramSocket.close();
                    this.datagramSocket = null;
                }
                MQClientManager.getInstance().removeClientFactory(this.clientId);
                log.info("the client factory [{}] shutdown OK", this.clientId);
                break;
            case SHUTDOWN_ALREADY:
                break;
            default:
                break;
            }
        }
    }


    private void recordSnapshotPeriodically() {
        Iterator<Entry<String, MQConsumerInner>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, MQConsumerInner> entry = it.next();
            MQConsumerInner impl = entry.getValue();
            if (impl != null) {
                if (impl instanceof DefaultMQPushConsumerImpl) {
                    DefaultMQPushConsumerImpl consumer = (DefaultMQPushConsumerImpl) impl;
                    consumer.getConsumerStatManager().recordSnapshotPeriodically();
                }
            }
        }
    }


    private void logStatsPeriodically() {
        Iterator<Entry<String, MQConsumerInner>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, MQConsumerInner> entry = it.next();
            MQConsumerInner impl = entry.getValue();
            if (impl != null) {
                if (impl instanceof DefaultMQPushConsumerImpl) {
                    DefaultMQPushConsumerImpl consumer = (DefaultMQPushConsumerImpl) impl;
                    consumer.getConsumerStatManager().logStatsPeriodically(entry.getKey(), this.clientId);
                }
            }
        }
    }


    private void persistAllConsumerOffset() {
        Iterator<Entry<String, MQConsumerInner>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, MQConsumerInner> entry = it.next();
            MQConsumerInner impl = entry.getValue();
            impl.persistConsumerOffset();
        }
    }


    private void unregisterClientWithLock(final String producerGroup, final String consumerGroup) {
        try {
            if (this.lockHeartbeat.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    this.unregisterClient(producerGroup, consumerGroup);
                }
                catch (Exception e) {
                    log.error("unregisterClient exception", e);
                }
                finally {
                    this.lockHeartbeat.unlock();
                }
            }
            else {
                log.warn("lock heartBeat, but failed.");
            }
        }
        catch (InterruptedException e) {
            log.warn("unregisterClientWithLock exception", e);
        }
    }


    private void unregisterClient(final String producerGroup, final String consumerGroup) {
        for (String name : this.brokerAddrTable.keySet()) {
            final HashMap<Long, String> oneTable = this.brokerAddrTable.get(name);
            if (oneTable != null) {
                for (Long id : oneTable.keySet()) {
                    String addr = oneTable.get(id);
                    if (addr != null) {
                        try {
                            this.mQClientAPIImpl.unregisterClient(addr, this.clientId, producerGroup,
                                consumerGroup, 3000);
                            log.info(
                                "unregister client[Producer: {} Consumer: {}] from broker[{} {} {}] success",
                                producerGroup, consumerGroup, name, id, addr);
                        }
                        catch (RemotingException e) {
                            log.error("unregister client exception from broker: " + addr, e);
                        }
                        catch (MQBrokerException e) {
                            log.error("unregister client exception from broker: " + addr, e);
                        }
                        catch (InterruptedException e) {
                            log.error("unregister client exception from broker: " + addr, e);
                        }
                    }
                }
            }
        }
    }


    private HeartbeatData prepareHeartbeatData() {
        HeartbeatData heartbeatData = new HeartbeatData();

        // clientID
        heartbeatData.setClientID(this.clientId);

        // Consumer
        for (String group : this.consumerTable.keySet()) {
            MQConsumerInner impl = this.consumerTable.get(group);
            if (impl != null) {
                ConsumerData consumerData = new ConsumerData();
                consumerData.setGroupName(impl.groupName());
                consumerData.setConsumeType(impl.consumeType());
                consumerData.setMessageModel(impl.messageModel());
                consumerData.setConsumeFromWhere(impl.consumeFromWhere());
                consumerData.getSubscriptionDataSet().addAll(impl.subscriptions());

                heartbeatData.getConsumerDataSet().add(consumerData);
            }
        }

        // Producer
        for (String group : this.producerTable.keySet()) {
            MQProducerInner impl = this.producerTable.get(group);
            if (impl != null) {
                ProducerData producerData = new ProducerData();
                producerData.setGroupName(group);

                heartbeatData.getProducerDataSet().add(producerData);
            }
        }

        return heartbeatData;
    }


    public void sendHeartbeatToAllBrokerWithLock() {
        if (this.lockHeartbeat.tryLock()) {
            try {
                this.sendHeartbeatToAllBroker();
            }
            catch (final Exception e) {
                log.error("sendHeartbeatToAllBroker exception", e);
            }
            finally {
                this.lockHeartbeat.unlock();
            }
        }
        else {
            log.warn("lock heartBeat, but failed.");
        }
    }


    private void sendHeartbeatToAllBroker() {
        final HeartbeatData heartbeatData = this.prepareHeartbeatData();
        final boolean producerEmpty = heartbeatData.getProducerDataSet().isEmpty();
        final boolean consumerEmpty = heartbeatData.getConsumerDataSet().isEmpty();
        if (producerEmpty && consumerEmpty) {
            log.warn("sending hearbeat, but no consumer and no producer");
            return;
        }

        for (String name : this.brokerAddrTable.keySet()) {
            final HashMap<Long, String> oneTable = this.brokerAddrTable.get(name);
            if (oneTable != null) {
                for (Long id : oneTable.keySet()) {
                    String addr = oneTable.get(id);
                    if (addr != null) {
                        // 说明只有Producer，则不向Slave发心跳
                        if (consumerEmpty) {
                            if (id != MixAll.MASTER_ID)
                                continue;
                        }

                        try {
                            this.mQClientAPIImpl.sendHearbeat(addr, heartbeatData, 3000);
                            log.debug("send heart beat to broker[{} {} {}] success", name, id, addr);
                            log.debug(heartbeatData.toString());
                        }
                        catch (Exception e) {
                            log.error("send heart beat to broker exception", e);
                        }
                    }
                }
            }
        }
    }


    public boolean registerConsumer(final String group, final MQConsumerInner consumer) {
        if (null == group || null == consumer) {
            return false;
        }

        MQConsumerInner prev = this.consumerTable.putIfAbsent(group, consumer);
        if (prev != null) {
            log.warn("the consumer group[" + group + "] exist already.");
            return false;
        }

        return true;
    }


    public void unregisterConsumer(final String group) {
        this.consumerTable.remove(group);
        this.unregisterClientWithLock(null, group);
    }


    public boolean registerProducer(final String group, final DefaultMQProducerImpl producer) {
        if (null == group || null == producer) {
            return false;
        }

        MQProducerInner prev = this.producerTable.putIfAbsent(group, producer);
        if (prev != null) {
            log.warn("the producer group[{}] exist already.", group);
            return false;
        }

        return true;
    }


    public void unregisterProducer(final String group) {
        this.producerTable.remove(group);
        this.unregisterClientWithLock(group, null);
    }


    public boolean registerAdminExt(final String group, final MQAdminExtInner admin) {
        if (null == group || null == admin) {
            return false;
        }

        MQAdminExtInner prev = this.adminExtTable.putIfAbsent(group, admin);
        if (prev != null) {
            log.warn("the admin group[{}] exist already.", group);
            return false;
        }

        return true;
    }


    public void unregisterAdminExt(final String group) {
        this.adminExtTable.remove(group);
    }


    public void rebalanceImmediately() {
        this.rebalanceService.wakeup();
    }


    public void doRebalance() {
        for (String group : this.consumerTable.keySet()) {
            MQConsumerInner impl = this.consumerTable.get(group);
            if (impl != null) {
                try {
                    impl.doRebalance();
                }
                catch (Exception e) {
                    log.error("doRebalance exception", e);
                }
            }
        }
    }


    public MQProducerInner selectProducer(final String group) {
        return this.producerTable.get(group);
    }


    public MQConsumerInner selectConsumer(final String group) {
        return this.consumerTable.get(group);
    }


    /**
     * 管理类的接口查询Broker地址，Master优先
     * 
     * @param brokerName
     * @return
     */
    public FindBrokerResult findBrokerAddressInAdmin(final String brokerName) {
        String brokerAddr = null;
        boolean slave = false;
        boolean found = false;

        HashMap<Long/* brokerId */, String/* address */> map = this.brokerAddrTable.get(brokerName);
        if (map != null && !map.isEmpty()) {
            // TODO 如果有多个Slave，可能会每次都选中相同的Slave，这里需要优化
            FOR_SEG: for (Map.Entry<Long, String> entry : map.entrySet()) {
                Long id = entry.getKey();
                brokerAddr = entry.getValue();
                if (brokerAddr != null) {
                    found = true;
                    if (MixAll.MASTER_ID == id) {
                        slave = false;
                        break FOR_SEG;
                    }
                    else {
                        slave = true;
                    }
                    break;

                }
            } // end of for
        }

        if (found) {
            return new FindBrokerResult(brokerAddr, slave);
        }

        return null;
    }


    /**
     * 发布消息过程中，寻找Broker地址，一定是找Master
     */
    public String findBrokerAddressInPublish(final String brokerName) {
        HashMap<Long/* brokerId */, String/* address */> map = this.brokerAddrTable.get(brokerName);
        if (map != null && !map.isEmpty()) {
            return map.get(MixAll.MASTER_ID);
        }

        return null;
    }


    /**
     * 订阅消息过程中，寻找Broker地址，取Master还是Slave由参数决定
     */
    public FindBrokerResult findBrokerAddressInSubscribe(//
            final String brokerName,//
            final long brokerId,//
            final boolean onlyThisBroker//
    ) {
        String brokerAddr = null;
        boolean slave = false;
        boolean found = false;

        HashMap<Long/* brokerId */, String/* address */> map = this.brokerAddrTable.get(brokerName);
        if (map != null && !map.isEmpty()) {
            brokerAddr = map.get(brokerId);
            slave = (brokerId != MixAll.MASTER_ID);
            found = (brokerAddr != null);

            // 尝试寻找其他Broker
            if (!found && !onlyThisBroker) {
                Entry<Long, String> entry = map.entrySet().iterator().next();
                brokerAddr = entry.getValue();
                slave = (entry.getKey() != MixAll.MASTER_ID);
                found = true;
            }
        }

        if (found) {
            return new FindBrokerResult(brokerAddr, slave);
        }

        return null;
    }


    public String findBrokerAddrByTopic(final String topic) {
        TopicRouteData topicRouteData = this.topicRouteTable.get(topic);
        if (topicRouteData != null) {
            List<BrokerData> brokers = topicRouteData.getBrokerDatas();
            if (!brokers.isEmpty()) {
                BrokerData bd = brokers.get(0);
                return bd.selectBrokerAddr();
            }
        }

        return null;
    }


    public List<String> findConsumerIdList(final String topic, final String group) {
        String brokerAddr = this.findBrokerAddrByTopic(topic);
        if (null == brokerAddr) {
            this.updateTopicRouteInfoFromNameServer(topic);
            brokerAddr = this.findBrokerAddrByTopic(topic);
        }

        if (null != brokerAddr) {
            try {
                return this.mQClientAPIImpl.getConsumerIdListByGroup(brokerAddr, group, 3000);
            }
            catch (Exception e) {
                log.warn("getConsumerIdListByGroup exception, " + brokerAddr + " " + group, e);
            }
        }

        return null;
    }


    public static TopicPublishInfo topicRouteData2TopicPublishInfo(final String topic,
            final TopicRouteData route) {
        TopicPublishInfo info = new TopicPublishInfo();
        // 顺序消息
        if (route.getOrderTopicConf() != null && route.getOrderTopicConf().length() > 0) {
            String[] brokers = route.getOrderTopicConf().split(";");
            for (String broker : brokers) {
                String[] item = broker.split(":");
                int nums = Integer.parseInt(item[1]);
                for (int i = 0; i < nums; i++) {
                    MessageQueue mq = new MessageQueue(topic, item[0], i);
                    info.getMessageQueueList().add(mq);
                }
            }

            info.setOrderTopic(true);
        }
        // 非顺序消息
        else {
            List<QueueData> qds = route.getQueueDatas();
            // 排序原因：即使没有配置顺序消息模式，默认队列的顺序同配置的一致。
            Collections.sort(qds);
            for (QueueData qd : qds) {
                if (PermName.isWriteable(qd.getPerm())) {
                    for (int i = 0; i < qd.getWriteQueueNums(); i++) {
                        MessageQueue mq = new MessageQueue(topic, qd.getBrokerName(), i);
                        info.getMessageQueueList().add(mq);
                    }
                }
            }

            info.setOrderTopic(false);
        }

        return info;
    }


    public static Set<MessageQueue> topicRouteData2TopicSubscribeInfo(final String topic,
            final TopicRouteData route) {
        Set<MessageQueue> mqList = new HashSet<MessageQueue>();
        List<QueueData> qds = route.getQueueDatas();
        for (QueueData qd : qds) {
            if (PermName.isReadable(qd.getPerm())) {
                for (int i = 0; i < qd.getReadQueueNums(); i++) {
                    MessageQueue mq = new MessageQueue(topic, qd.getBrokerName(), i);
                    mqList.add(mq);
                }
            }
        }

        return mqList;
    }


    private void updateTopicRouteInfoFromNameServer() {
        Set<String> topicList = new HashSet<String>();

        // Consumer
        {
            Iterator<Entry<String, MQConsumerInner>> it = this.consumerTable.entrySet().iterator();
            while (it.hasNext()) {
                Entry<String, MQConsumerInner> entry = it.next();
                MQConsumerInner impl = entry.getValue();
                if (impl != null) {
                    Set<SubscriptionData> subList = impl.subscriptions();
                    if (subList != null) {
                        for (SubscriptionData subData : subList) {
                            topicList.add(subData.getTopic());
                        }
                    }
                }
            }
        }

        // Producer
        {
            Iterator<Entry<String, MQProducerInner>> it = this.producerTable.entrySet().iterator();
            while (it.hasNext()) {
                Entry<String, MQProducerInner> entry = it.next();
                MQProducerInner impl = entry.getValue();
                if (impl != null) {
                    Set<String> lst = impl.getPublishTopicList();
                    topicList.addAll(lst);
                }
            }
        }

        for (String topic : topicList) {
            this.updateTopicRouteInfoFromNameServer(topic);
        }
    }


    public TopicRouteData getAnExistTopicRouteData(final String topic) {
        return this.topicRouteTable.get(topic);
    }


    /**
     * 调用Name Server接口，根据Topic获取路由信息
     */
    public boolean updateTopicRouteInfoFromNameServer(final String topic) {
        try {
            if (this.lockNamesrv.tryLock(LockTimeoutMillis, TimeUnit.MILLISECONDS)) {
                try {
                    TopicRouteData topicRouteData =
                            this.mQClientAPIImpl.getTopicRouteInfoFromNameServer(topic, 1000 * 3);
                    if (topicRouteData != null) {
                        TopicRouteData old = this.topicRouteTable.get(topic);
                        if (null == old || !old.equals(topicRouteData)) {
                            log.info("the topic[" + topic + "] route info changed, " + topicRouteData);
                            // 更新Broker地址信息
                            for (BrokerData bd : topicRouteData.getBrokerDatas()) {
                                this.brokerAddrTable.put(bd.getBrokerName(), bd.getBrokerAddrs());
                            }

                            // 更新发布队列信息
                            {
                                TopicPublishInfo publishInfo =
                                        topicRouteData2TopicPublishInfo(topic, topicRouteData);
                                Iterator<Entry<String, MQProducerInner>> it =
                                        this.producerTable.entrySet().iterator();
                                while (it.hasNext()) {
                                    Entry<String, MQProducerInner> entry = it.next();
                                    MQProducerInner impl = entry.getValue();
                                    if (impl != null) {
                                        impl.updateTopicPublishInfo(topic, publishInfo);
                                    }
                                }
                            }

                            // 更新订阅队列信息
                            {
                                Set<MessageQueue> subscribeInfo =
                                        topicRouteData2TopicSubscribeInfo(topic, topicRouteData);
                                Iterator<Entry<String, MQConsumerInner>> it =
                                        this.consumerTable.entrySet().iterator();
                                while (it.hasNext()) {
                                    Entry<String, MQConsumerInner> entry = it.next();
                                    MQConsumerInner impl = entry.getValue();
                                    if (impl != null) {
                                        impl.updateTopicSubscribeInfo(topic, subscribeInfo);
                                    }
                                }
                            }

                            this.topicRouteTable.put(topic, topicRouteData);
                            return true;
                        }
                    }
                    else {
                        log.warn(
                            "updateTopicRouteInfoFromNameServer, getTopicRouteInfoFromNameServer return null, Topic: {}",
                            topic);
                    }
                }
                catch (Exception e) {
                    if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        log.warn("updateTopicRouteInfoFromNameServer Exception", e);
                    }
                }
                finally {
                    this.lockNamesrv.unlock();
                }
            }
            else {
                log.warn("updateTopicRouteInfoFromNameServer tryLock timeout {}ms", LockTimeoutMillis);
            }
        }
        catch (InterruptedException e) {
            log.warn("updateTopicRouteInfoFromNameServer Exception", e);
        }

        return false;
    }


    public MQClientAPIImpl getMQClientAPIImpl() {
        return mQClientAPIImpl;
    }


    public MQAdminImpl getMQAdminImpl() {
        return mQAdminImpl;
    }


    public String getClientId() {
        return clientId;
    }


    public long getBootTimestamp() {
        return bootTimestamp;
    }


    public ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }


    public PullMessageService getPullMessageService() {
        return pullMessageService;
    }


    public DefaultMQProducer getDefaultMQProducer() {
        return defaultMQProducer;
    }
}

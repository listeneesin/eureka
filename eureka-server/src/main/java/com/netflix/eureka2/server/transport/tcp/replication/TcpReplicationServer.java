/*
 * Copyright 2014 Netflix, Inc.
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

package com.netflix.eureka2.server.transport.tcp.replication;

import com.google.inject.Singleton;
import com.netflix.eureka2.server.config.EurekaServerConfig;
import com.netflix.eureka2.server.metric.EurekaServerMetricFactory;
import com.netflix.eureka2.server.registry.EurekaServerRegistry;
import com.netflix.eureka2.server.registry.eviction.EvictionQueue;
import com.netflix.eureka2.server.transport.tcp.AbstractTcpServer;
import com.netflix.eureka2.transport.EurekaTransports;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.metrics.MetricEventsListenerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * @author Tomasz Bak
 */
@Singleton
public class TcpReplicationServer extends AbstractTcpServer {

    private static final Logger logger = LoggerFactory.getLogger(TcpReplicationServer.class);

    private final EvictionQueue evictionQueue;

    @Inject
    public TcpReplicationServer(EurekaServerConfig config,
                                EurekaServerRegistry eurekaRegistry,
                                EvictionQueue evictionQueue,
                                @Named("replication") MetricEventsListenerFactory servoEventsListenerFactory,
                                EurekaServerMetricFactory metricFactory) {
        super(eurekaRegistry, servoEventsListenerFactory, config, metricFactory);
        this.evictionQueue = evictionQueue;
    }

    @PostConstruct
    public void start() {
        server = RxNetty.newTcpServerBuilder(
                config.getReplicationPort(),
                new TcpReplicationHandler(eurekaRegistry, evictionQueue, metricFactory))
                .pipelineConfigurator(EurekaTransports.replicationPipeline(config.getCodec()))
                .withMetricEventsListenerFactory(servoEventsListenerFactory)
                .build()
                .start();

        logger.info("Starting TCP replication server on port {} with {} encoding...", server.getServerPort(), config.getCodec());
    }

}
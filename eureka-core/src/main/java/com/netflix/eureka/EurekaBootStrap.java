/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 *
 */
//这个类实现了ServletContextLister接口，在web.xml里注册了该监听器，程序启动时，会执行这个监听器的初始化方法
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    private static final String CLOUD = "cloud";
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    protected volatile EurekaServerContext serverContext;
    protected volatile AwsBinder awsBinder;
    
    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }
    
    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     * 
     * @param eurekaClient the eureka client to bootstrap
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     *
     * @see
     * javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */
    //eureka-server启动会执行的初始化方法
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            //初始化Eureka-server的环境
            initEurekaEnvironment();
            //初始化Eureka-server上下文信息
            initEurekaServerContext();

            ServletContext sc = event.getServletContext();
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");

        /**
         * ConfigurationManager.getConfigInstance()这个方法名就可以看出是获取一个配置的实例，ConfigurationManager名字上
         * 就可以看出是一个配置管理器，我们猜测一下，这里其实是通过一个配置管理器创建了一个配置实例，这个配置实例中包含了一些信息，
         * 然后这个dataCenter就是从这个实例中根据某个KEY获取的一个value值，当然，如果是这样，那么这个配置实例应该是单例的，我们
         * 进入这个方法内看一看
         *
         * 果不其然，双重检查锁构建的一个单例的配置对象：AbstractConfiguration，这个是个抽奖类，具体创建的配置实例应该是它的一
         * 个子类：ConcurrentCompositeConfiguration；从名字上也可以看出，这是一个线程安全的、复合的配置类，它里面维护了一个
         * 线程安全的List容器，里面放了一堆子配置类，我们具体不做深究
         */
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);
        if (dataCenter == null) {
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to default");
            //通过上面的分析这里就很好理解，获取这个单例的、线程安全的、复合的、配置实例对象，如果没有获取到数据中心，则给它设个默认值
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
        } else {
            //如果获取到了，则设置为获取到的dataCenter
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
        }
        //从这个单例的、线程安全的、复合的、配置实例对象中获取eureka-environment
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        if (environment == null) {
            //不存在则设置为测试环境
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     */
    protected void initEurekaServerContext() throws Exception {
        /**
         * 这里看类名不难猜出，这是和Eureka-server配置相关的，我们通过点击EurekaServerConfig发现，这是一个接口类，里面定义了
         * 一堆获取Eureka-server相关配置信息的接口。它的实现的DefaultEurekaServerConfig应该会为它初始化相关配置，并保存在某个
         * 类变量中，最后就可以通过这个接口类来获取到Eureka-server相关配置
         *
         * 我们进入DefaultEurekaServerConfig看一看
         */
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        //跳过。。
        // For backward compatibility
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        //编解码相关，跳过。。。
        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);
        /**
         * 这里又创建了一个Manager，我们要区分清楚，之前的有一个叫ConfigurationManager，根据我们目前读到的信息，它管理的主要是
         * 环境相关配置和Eureka-server相关配置信息，我们来看看这个管理器是干啥的
         */
        ApplicationInfoManager applicationInfoManager = null;

        //刚进来，eureka肯定是null，这里为null进入分支应该是要初始化EurekaClient
        if (eurekaClient == null) {
            /**
             * 之前又EurekaServerConfig，这里是EurekaInstanceConfig，点击看，它同样是一个接口类，提供了Eureka-instance的
             * 相关配置信息，我们不在云上部署，所以初始化配置信息的实现类是MyDataCenterInstanceConfig
             * 简单点进去看了下，和初始化EurekaServerConfig相关配置信息的方法差不多，也是读取配置文件，加载到其中，有意思的是，
             * 具体的配置信息同样放到了ConfigurationManger管理的那个单例的、线程安全的、复合的配置类中，所以这个管理器管理的那个
             * 配置实例不仅包含了Eureka-server,还有Eureka-instance
             */
            EurekaInstanceConfig instanceConfig = isCloud(ConfigurationManager.getDeploymentContext())
                    ? new CloudInstanceConfig()
                    : new MyDataCenterInstanceConfig();
            /**
             * 这里是两步，一步是拿初始化好的instanceConfig实例转变成为InstanceInfo对象，第二步是用这个，用EurekaInstanceConfig
             * 和InstanceInfo构造ApplicationInfoManager
             */
            applicationInfoManager = new ApplicationInfoManager(
                    instanceConfig, new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());
            /**
             * 又来了，熟悉的套路，EurekaClientConfig为获取配置信息的接口类，具体在DefaultEurekaClientConfig中读取配置文件，
             * 保存到ConfigurationManager的单例的、线程安全的、复合的那个配置实例中去
             *
             */
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
            /**
             * applicationInfoManager + eurekaClientConfig 构造eurekaClient对象，这两个配置相关一个注重的Instance,
             * 一个注重的client，其实不难理解，正常的微服务中，作为注册中心的就是eureka-server，充当服务提供者和服务调用者的
             * 服务一方面充当Instance服务实例，一方面是eureka-client，侧重点不同而已
             *
             * 我们去具体实现的DiscoveryClient看一看
             */
            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }

        /**
         * 总结一下，上面创建eurekaClient的时候，干了很多事。。。
         * 抓大放小的来说
         *  1. 初始化了一堆配置
         *  2. 创建了一堆调度任务 （抓取注册表调度任务，注册注册表调度任务）
         *  3. 注册 + 抓取注册表 （如果设置了初始化的时候开启注册和抓取的话）    //这里传递的是instanceInfo,而不是InstanceRegistry，后面比较区别...
         *  4. InstanceInfo变更的话，回调监听器开始执行副本复制到Eureka-server （如果状态变更允许即可更新）
         *
         *  注：这里是eureka-server的启动，在eureka集群中，每个eureka-server都是属于PeerToPeer的节点，每个eureka-server也都会向集群
         *  中其它eureka-server发送推送和抓取注册表的任务，所以和eureka-client干的活有点类似，不奇怪
         */

        /******************************       华丽的分割线         ***********************************/

        //这是注册表，看样子要初始化注册表了
        PeerAwareInstanceRegistry registry;
        if (isAws(applicationInfoManager.getInfo())) {
            registry = new AwsInstanceRegistry(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
            awsBinder = new AwsBinderDelegate(eurekaServerConfig, eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            //走到这个分支，用一堆配置初始化这个注册表
            registry = new PeerAwareInstanceRegistryImpl(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
        }
        //这个看类方法有点像管理eureka集群相关的东西
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );
        //eureka-server的上下文
        serverContext = new DefaultEurekaServerContext(
                eurekaServerConfig,
                serverCodecs,
                registry,
                peerEurekaNodes,
                applicationInfoManager
        );
        //以后从EurekaServerContextHolder就能拿到EurekaServerContext
        EurekaServerContextHolder.initialize(serverContext);
        //serverContext初始化，点进去看下
        serverContext.initialize();
        logger.info("Initialized server context");

        // Copy registry from neighboring eureka node
        int registryCount = registry.syncUp();
        registry.openForTraffic(applicationInfoManager, registryCount);

        // Register all monitoring statistics.
        EurekaMonitors.registerAllStats();
    }
    
    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig eurekaServerConfig, EurekaClientConfig eurekaClientConfig, ServerCodecs serverCodecs, ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClientConfig,
                serverCodecs,
                applicationInfoManager
        );
        
        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());

            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}

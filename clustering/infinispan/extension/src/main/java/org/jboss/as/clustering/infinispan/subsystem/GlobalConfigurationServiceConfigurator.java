/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.clustering.infinispan.subsystem;

import static org.jboss.as.clustering.infinispan.subsystem.CacheContainerResourceDefinition.Attribute.DEFAULT_CACHE;
import static org.jboss.as.clustering.infinispan.subsystem.CacheContainerResourceDefinition.Attribute.MARSHALLER;
import static org.jboss.as.clustering.infinispan.subsystem.CacheContainerResourceDefinition.Attribute.STATISTICS_ENABLED;
import static org.jboss.as.clustering.infinispan.subsystem.CacheContainerResourceDefinition.Capability.CONFIGURATION;

import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.management.MBeanServer;

import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.commons.util.AggregatedClassLoader;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.ShutdownHookBehavior;
import org.infinispan.configuration.global.ThreadPoolConfiguration;
import org.infinispan.configuration.global.TransportConfiguration;
import org.infinispan.configuration.internal.PrivateGlobalConfigurationBuilder;
import org.infinispan.protostream.SerializationContext;
import org.infinispan.protostream.SerializationContextInitializer;
import org.jboss.as.clustering.controller.CapabilityServiceNameProvider;
import org.jboss.as.clustering.controller.CommonRequirement;
import org.jboss.as.clustering.controller.ResourceServiceConfigurator;
import org.jboss.as.clustering.infinispan.InfinispanLogger;
import org.jboss.as.clustering.infinispan.MBeanServerProvider;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.server.Services;
import org.jboss.dmr.ModelNode;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleLoader;
import org.jboss.msc.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceTarget;
import org.wildfly.clustering.infinispan.marshall.InfinispanMarshallerFactory;
import org.wildfly.clustering.service.CompositeDependency;
import org.wildfly.clustering.service.Dependency;
import org.wildfly.clustering.service.FunctionalService;
import org.wildfly.clustering.service.ServiceConfigurator;
import org.wildfly.clustering.service.ServiceSupplierDependency;
import org.wildfly.clustering.service.SupplierDependency;

/**
 * @author Paul Ferraro
 */
public class GlobalConfigurationServiceConfigurator extends CapabilityServiceNameProvider implements ResourceServiceConfigurator, Supplier<GlobalConfiguration> {

    private final SupplierDependency<ModuleLoader> loader;
    private final SupplierDependency<List<Module>> modules;
    private final SupplierDependency<TransportConfiguration> transport;
    private final Map<ThreadPoolResourceDefinition, SupplierDependency<ThreadPoolConfiguration>> pools = new EnumMap<>(ThreadPoolResourceDefinition.class);
    private final Map<ScheduledThreadPoolResourceDefinition, SupplierDependency<ThreadPoolConfiguration>> scheduledPools = new EnumMap<>(ScheduledThreadPoolResourceDefinition.class);
    private final String name;

    private volatile SupplierDependency<MBeanServer> server;
    private volatile String defaultCache;
    private volatile boolean statisticsEnabled;
    private volatile InfinispanMarshallerFactory marshallerFactory;

    GlobalConfigurationServiceConfigurator(PathAddress address) {
        super(CONFIGURATION, address);
        this.name = address.getLastElement().getValue();
        this.loader = new ServiceSupplierDependency<>(Services.JBOSS_SERVICE_MODULE_LOADER);
        this.modules = new ServiceSupplierDependency<>(CacheContainerComponent.MODULES.getServiceName(address));
        this.transport = new ServiceSupplierDependency<>(CacheContainerComponent.TRANSPORT.getServiceName(address));
        for (ThreadPoolResourceDefinition pool : EnumSet.of(ThreadPoolResourceDefinition.LISTENER, ThreadPoolResourceDefinition.BLOCKING, ThreadPoolResourceDefinition.NON_BLOCKING)) {
            this.pools.put(pool, new ServiceSupplierDependency<>(pool.getServiceName(address)));
        }
        for (ScheduledThreadPoolResourceDefinition pool : EnumSet.allOf(ScheduledThreadPoolResourceDefinition.class)) {
            this.scheduledPools.put(pool, new ServiceSupplierDependency<>(pool.getServiceName(address)));
        }
    }

    @Override
    public ServiceConfigurator configure(OperationContext context, ModelNode model) throws OperationFailedException {
        this.server = context.hasOptionalCapability(CommonRequirement.MBEAN_SERVER.getName(), null, null) ? new ServiceSupplierDependency<>(CommonRequirement.MBEAN_SERVER.getServiceName(context)) : null;
        this.defaultCache = DEFAULT_CACHE.resolveModelAttribute(context, model).asStringOrNull();
        this.statisticsEnabled = STATISTICS_ENABLED.resolveModelAttribute(context, model).asBoolean();
        this.marshallerFactory = InfinispanMarshallerFactory.valueOf(MARSHALLER.resolveModelAttribute(context, model).asString());
        return this;
    }

    @Override
    public GlobalConfiguration get() {
        org.infinispan.configuration.global.GlobalConfigurationBuilder builder = new org.infinispan.configuration.global.GlobalConfigurationBuilder();
        builder.cacheManagerName(this.name)
                .defaultCacheName(this.defaultCache)
                .cacheContainer().statistics(this.statisticsEnabled)
        ;

        builder.transport().read(this.transport.get());

        List<Module> modules = this.modules.get();
        Marshaller marshaller = this.marshallerFactory.apply(this.loader.get(), modules);
        InfinispanLogger.ROOT_LOGGER.debugf("%s cache-container will use %s", this.name, marshaller.getClass().getName());
        // Register dummy serialization context initializer, to bypass service loading in org.infinispan.marshall.protostream.impl.SerializationContextRegistryImpl
        // Otherwise marshaller auto-detection will not work
        builder.serialization().marshaller(marshaller).addContextInitializer(new SerializationContextInitializer() {
            @Deprecated
            @Override
            public String getProtoFile() throws UncheckedIOException {
                return null;
            }

            @Deprecated
            @Override
            public String getProtoFileName() {
                return null;
            }

            @Override
            public void registerMarshallers(SerializationContext context) {
            }

            @Override
            public void registerSchema(SerializationContext context) {
            }
        });

        builder.classLoader(modules.size() > 1 ? new AggregatedClassLoader(modules.stream().map(Module::getClassLoader).collect(Collectors.toList())) : modules.get(0).getClassLoader());

        builder.blockingThreadPool().read(this.pools.get(ThreadPoolResourceDefinition.BLOCKING).get());
        builder.listenerThreadPool().read(this.pools.get(ThreadPoolResourceDefinition.LISTENER).get());
        builder.nonBlockingThreadPool().read(this.pools.get(ThreadPoolResourceDefinition.NON_BLOCKING).get());
        builder.expirationThreadPool().read(this.scheduledPools.get(ScheduledThreadPoolResourceDefinition.EXPIRATION).get());

        builder.shutdown().hookBehavior(ShutdownHookBehavior.DONT_REGISTER);
        // Disable registration of MicroProfile Metrics
        builder.metrics().gauges(false).histograms(false).accurateSize(true);
        builder.jmx().domain("org.wildfly.clustering.infinispan")
                .mBeanServerLookup((this.server != null) ? new MBeanServerProvider(this.server.get()) : null)
                .enabled(this.server != null)
                ;

        // Do not enable server-mode for the Hibernate 2LC use case:
        // * The 2LC stack already overrides the interceptor for distribution caches
        // * This renders Infinispan default 2LC configuration unusable as it results in a default media type of application/unknown for keys and values
        // See ISPN-12252 for details
        if (modules.stream().map(Module::getName).noneMatch("org.infinispan.hibernate-cache"::equals)) {
            // Disable triangle algorithm - we optimize for originator as primary owner
            builder.addModule(PrivateGlobalConfigurationBuilder.class).serverMode(true);
        }
        builder.globalState().disable();

        return builder.build();
    }

    @Override
    public ServiceBuilder<?> build(ServiceTarget target) {
        ServiceBuilder<?> builder = target.addService(this.getServiceName());
        Consumer<GlobalConfiguration> global = new CompositeDependency(this.loader, this.modules, this.transport, this.server).register(builder).provides(this.getServiceName());
        for (Dependency dependency: this.pools.values()) {
            dependency.register(builder);
        }
        for (Dependency dependency: this.scheduledPools.values()) {
            dependency.register(builder);
        }
        Service service = new FunctionalService<>(global, Function.identity(), this);
        return builder.setInstance(service).setInitialMode(ServiceController.Mode.PASSIVE);
    }
}

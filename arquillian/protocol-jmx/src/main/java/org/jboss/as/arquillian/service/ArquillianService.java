/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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

package org.jboss.as.arquillian.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServer;

import org.jboss.arquillian.protocol.jmx.JMXTestRunner;
import org.jboss.arquillian.testenricher.osgi.BundleContextAssociation;
import org.jboss.as.jmx.MBeanServerService;
import org.jboss.as.osgi.OSGiConstants;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.Phase;
import org.jboss.as.server.deployment.SetupAction;
import org.wildfly.security.manager.GetContextClassLoaderAction;
import org.wildfly.security.manager.SetContextClassLoaderAction;
import org.jboss.logging.Logger;
import org.jboss.modules.Module;
import org.jboss.msc.service.AbstractServiceListener;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.jboss.osgi.resolver.XBundleRevision;
import org.osgi.framework.BundleContext;

import static java.lang.System.getSecurityManager;
import static java.lang.Thread.currentThread;
import static java.security.AccessController.doPrivileged;
import static org.jboss.as.server.deployment.Services.JBOSS_DEPLOYMENT;

/**
 * Service responsible for creating and managing the life-cycle of the Arquillian service.
 *
 * @author Thomas.Diesler@jboss.com
 * @author Kabir Khan
 * @since 17-Nov-2010
 */
public class ArquillianService implements Service<ArquillianService> {

    public static final String TEST_CLASS_PROPERTY = "org.jboss.as.arquillian.testClass";
    public static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("arquillian", "testrunner");

    private static final Logger log = Logger.getLogger("org.jboss.as.arquillian");

    private final InjectedValue<MBeanServer> injectedMBeanServer = new InjectedValue<MBeanServer>();
    private final Set<ArquillianConfig> deployedTests = new HashSet<ArquillianConfig>();
    private ServiceContainer serviceContainer;
    private ServiceTarget serviceTarget;
    private JMXTestRunner jmxTestRunner;
    AbstractServiceListener<Object> listener;

    public static void addService(final ServiceTarget serviceTarget) {
        ArquillianService service = new ArquillianService();
        ServiceBuilder<?> builder = serviceTarget.addService(ArquillianService.SERVICE_NAME, service);
        builder.addDependency(MBeanServerService.SERVICE_NAME, MBeanServer.class, service.injectedMBeanServer);
        builder.install();
    }

    ServiceContainer getServiceContainer() {
        return serviceContainer;
    }

    public synchronized void start(StartContext context) throws StartException {
        log.debugf("Starting Arquillian Test Runner");

        final MBeanServer mbeanServer = injectedMBeanServer.getValue();
        serviceContainer = context.getController().getServiceContainer();
        serviceTarget = context.getChildTarget();
        try {
            jmxTestRunner = new ExtendedJMXTestRunner();
            jmxTestRunner.registerMBean(mbeanServer);
        } catch (Throwable t) {
            throw new StartException("Failed to start Arquillian Test Runner", t);
        }

        final ArquillianService arqService = this;
        listener = new AbstractServiceListener<Object>() {

            @Override
            public void transition(ServiceController<? extends Object> serviceController, ServiceController.Transition transition) {
                switch (transition.getAfter()) {
                    case UP: {
                        ServiceName serviceName = serviceController.getName();
                        String simpleName = serviceName.getSimpleName();
                        if (JBOSS_DEPLOYMENT.isParentOf(serviceName) && simpleName.equals(Phase.INSTALL.toString())) {
                            ServiceName parentName = serviceName.getParent();
                            ServiceController<?> parentController = serviceContainer.getService(parentName);
                            DeploymentUnit depUnit = (DeploymentUnit) parentController.getValue();
                            ArquillianConfig arqConfig = ArquillianConfigBuilder.processDeployment(arqService, depUnit);
                            if (arqConfig != null) {
                                log.infof("Arquillian deployment detected: %s", arqConfig);
                                ServiceBuilder<ArquillianConfig> builder = arqConfig.buildService(serviceTarget, serviceController);
                                builder.install();
                            }
                        }
                    }
                    case STARTING: {
                        ServiceName serviceName = serviceController.getName();
                        String simpleName = serviceName.getSimpleName();
                        if(JBOSS_DEPLOYMENT.isParentOf(serviceName) && simpleName.equals(Phase.DEPENDENCIES.toString())) {
                            ServiceName parentName = serviceName.getParent();
                            ServiceController<?> parentController = serviceContainer.getService(parentName);
                            DeploymentUnit depUnit = (DeploymentUnit) parentController.getValue();
                            ArquillianConfigBuilder.handleParseAnnotations(depUnit);
                        }
                    }
                }
            }
        };
        serviceContainer.addListener(listener);
    }

    public synchronized void stop(StopContext context) {
        log.debugf("Stopping Arquillian Test Runner");
        try {
            if (jmxTestRunner != null) {
                jmxTestRunner.unregisterMBean(injectedMBeanServer.getValue());
            }
        } catch (Exception ex) {
            log.errorf(ex, "Cannot stop Arquillian Test Runner");
        } finally {
            context.getController().getServiceContainer().removeListener(listener);
        }
    }

    @Override
    public synchronized ArquillianService getValue() throws IllegalStateException {
        return this;
    }

    void registerArquillianConfig(ArquillianConfig arqConfig) {
        synchronized (deployedTests) {
            log.debugf("Register Arquillian config: %s", arqConfig.getServiceName());
            deployedTests.add(arqConfig);
            deployedTests.notifyAll();
        }
    }

    void unregisterArquillianConfig(ArquillianConfig arqConfig) {
        synchronized (deployedTests) {
            log.debugf("Unregister Arquillian config: %s", arqConfig.getServiceName());
            deployedTests.remove(arqConfig);
        }
    }

    private ArquillianConfig getArquillianConfig(final String className, long timeout) {
        synchronized (deployedTests) {

            log.debugf("Getting Arquillian config for: %s", className);
            for (ArquillianConfig arqConfig : deployedTests) {
                for (String aux : arqConfig.getTestClasses()) {
                    if (aux.equals(className)) {
                        log.debugf("Found Arquillian config for: %s", className);
                        return arqConfig;
                    }
                }
            }

            if (timeout <= 0) {
                throw new IllegalStateException("Cannot obtain Arquillian config for: " + className);
            }

            try {
                log.debugf("Waiting on Arquillian config for: %s", className);
                deployedTests.wait(timeout);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        return getArquillianConfig(className, -1);
    }

    class ExtendedJMXTestRunner extends JMXTestRunner {

        ExtendedJMXTestRunner() {
            super(new ExtendedTestClassLoader());
        }

        @Override
        public byte[] runTestMethod(final String className, final String methodName) {
            ArquillianConfig arqConfig = getArquillianConfig(className, 30000L);
            Map<String, Object> properties = Collections.<String, Object>singletonMap(TEST_CLASS_PROPERTY, className);
            ContextManager contextManager = initializeContextManager(arqConfig, properties);
            try {
                return super.runTestMethod(className, methodName);
            } finally {
                contextManager.teardown(properties);
            }
        }

        private ContextManager initializeContextManager(final ArquillianConfig config, final Map<String, Object> properties) {
            final ContextManagerBuilder builder = new ContextManagerBuilder();
            final DeploymentUnit depUnit = config.getDeploymentUnit();
            final XBundleRevision brev = depUnit.getAttachment(OSGiConstants.BUNDLE_REVISION_KEY);
            final Module module = depUnit.getAttachment(Attachments.MODULE);
            if (brev == null && module != null) {
                builder.add(new TCCLSetupAction(module.getClassLoader()));
            }
            builder.addAll(depUnit);
            ContextManager contextManager = builder.build();
            contextManager.setup(properties);
            return contextManager;
        }
    }

    class ExtendedTestClassLoader implements JMXTestRunner.TestClassLoader {

        @Override
        public Class<?> loadTestClass(final String className) throws ClassNotFoundException {

            final ArquillianConfig arqConfig = getArquillianConfig(className, -1);
            if (arqConfig == null)
                throw new ClassNotFoundException("No Arquillian config found for: " + className);

            // Make the BundleContext available to the {@link OSGiTestEnricher}
            BundleContext bundleContext = arqConfig.getBundleContext();
            BundleContextAssociation.setBundleContext(bundleContext);

            return arqConfig.loadClass(className);
        }
    }

    /**
     * Sets and restores the Thread Context ClassLoader
     *
     * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
     * @author Stuart Douglas
     */
    private static final class TCCLSetupAction implements SetupAction {
        private final ThreadLocal<ClassLoader> oldClassLoader = new ThreadLocal<ClassLoader>();

        private final ClassLoader classLoader;

        TCCLSetupAction(final ClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        @Override
        public int priority() {
            return 10000;
        }

        @Override
        public Set<ServiceName> dependencies() {
            return Collections.emptySet();
        }

        @Override
        public void setup(Map<String, Object> properties) {
            oldClassLoader.set(getTccl());
            setTccl(classLoader);
        }

        @Override
        public void teardown(Map<String, Object> properties) {
            ClassLoader old = oldClassLoader.get();
            oldClassLoader.remove();
            setTccl(old);
        }
    }

    private static ClassLoader getTccl() {
        return getSecurityManager() == null ? currentThread().getContextClassLoader() : doPrivileged(GetContextClassLoaderAction.getInstance());
    }

    private static void setTccl(final ClassLoader cl) {
        assert cl != null : "ClassLoader must be specified";
        if (getSecurityManager() == null) {
            currentThread().setContextClassLoader(cl);
        } else {
            doPrivileged(new SetContextClassLoaderAction(cl));
        }
    }
}

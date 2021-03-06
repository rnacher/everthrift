/*
 * Copyright 2012-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.everthrift.appserver;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import org.everthrift.appserver.configs.AppserverConfig;
import org.everthrift.appserver.configs.AsyncTcpThrift;
import org.everthrift.appserver.configs.JGroups;
import org.everthrift.appserver.configs.LoopbackJGroups;
import org.everthrift.appserver.configs.TcpThrift;
import org.everthrift.thrift.MetaDataMapBuilder;
import org.everthrift.utils.SocketUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class AppserverApplication {

    private static final Logger log = LoggerFactory.getLogger(AppserverApplication.class);

    public final static AppserverApplication INSTANCE = new AppserverApplication();

    public final AnnotationConfigApplicationContext context;

    public final ConfigurableEnvironment env;

    private final List<String> thriftScanPathList = new ArrayList<String>();

    private boolean initialized = false;

    private static class AppResourcePropertySource extends ResourcePropertySource {
        private final String location;

        public AppResourcePropertySource(String location) throws IOException {
            super(location);
            this.location = location;
        }
    }

    private final List<PropertySource<?>> propertySourceList = new ArrayList<>();

    private List<Class<?>> annotatedClasses = Lists.newArrayList(AppserverConfig.class);

    private AppserverApplication() {

        System.setProperty("jgroups.logging.log_factory_class", "org.everthrift.appserver.cluster.JGroupsLogFactory");

        context = new AnnotationConfigApplicationContext();
        context.registerShutdownHook();
        env = context.getEnvironment();

        thriftScanPathList.add("org.everthrift.appserver");
    }

    private boolean isJettyEnabled() {
        return !env.getProperty("jetty", "false").equalsIgnoreCase("false");
    }

    private boolean isThriftEnabled() {
        return !env.getProperty("thrift", "false").equalsIgnoreCase("false");
    }

    private boolean isAsyncThriftEnabled() {
        return !env.getProperty("thrift.async", "false").equalsIgnoreCase("false");
    }

    public static boolean isJGroupsEnabled(Environment env) {
        return !env.getProperty("jgroups", "false").equalsIgnoreCase("false");
    }

    private boolean isJGroupsEnabled() {
        return isJGroupsEnabled(env);
    }

    private boolean isJmsEnabled() {
        return !env.getProperty("jms", "false").equalsIgnoreCase("false");
    }

    private boolean isRabbitEnabled() {
        return !env.getProperty("rabbit", "false").equalsIgnoreCase("false");
    }

    public void init(String[] args, String version) {
        try {
            _init(args, version);
        }catch (Exception e){
            //Могут оставаться (и остаются от Cassandra) не daemon потоки,
            //которые блокируют завершение JVM
            log.error("Exception while initializing app", e);
            System.exit(255);
        }
    }

    @SuppressWarnings("rawtypes")
    private synchronized void _init(String[] args, String version) {

        final Resource resource = context.getResource("classpath:application.properties");

        try {
            final ResourcePropertySource ps = new ResourcePropertySource(resource);
            env.getPropertySources().addFirst(ps);
        } catch (IOException e1) {
            log.error("Coudn't load application.properties", e1);
        }

        final PropertySource sclps = new SimpleCommandLinePropertySource(args);
        env.getPropertySources().addFirst(sclps);
        env.getPropertySources()
           .addLast(new MapPropertySource("thriftScanPathList", Collections.singletonMap("thrift.scan", (Object) thriftScanPathList)));
        env.getPropertySources()
           .addLast(new MapPropertySource("version", Collections.singletonMap("version", (Object) version)));

        PropertySource lastAdded = null;
        final Pattern resourcePattern = Pattern.compile("(.+)\\.properties");
        final String profile = env.getProperty("spring.profiles.active");

        for (PropertySource p : propertySourceList) {
            if (lastAdded == null) {
                env.getPropertySources().addAfter(sclps.getName(), p);
            } else {
                env.getPropertySources().addBefore(lastAdded.getName(), p);
            }
            lastAdded = p;
            if (p instanceof AppResourcePropertySource && profile != null) {
                final Matcher m = resourcePattern.matcher(((AppResourcePropertySource) p).location);
                if (m.matches()) {
                    try {
                        final String profileResourceName = String.format("%s-%s.properties", m.group(1), profile);
                        env.getPropertySources().addBefore(lastAdded.getName(),
                                                           (lastAdded = new ResourcePropertySource(profileResourceName)));
                        log.info("Added property source: {}", profileResourceName);
                    } catch (IOException e) {
                    }
                }
            }
        }

        if (Boolean.parseBoolean(env.getProperty("sqlmigrator.run", "false"))) {
            try {
                runSqlMigrator();
            } catch (Exception e) {
                throw Throwables.propagate(e);
            }
        }

        if (Boolean.parseBoolean(env.getProperty("cassandra.migrator.run", "false"))) {
            try {
                runCMigrationProcessor();
            } catch (Exception e) {
                throw Throwables.propagate(e);
            }
        }

        final boolean nothrift = !env.getProperty("nothrift", "false").equalsIgnoreCase("false");

        try {
            if (env.getProperty("thrift.async.port").equals("0")) {
                addProperty("thrift.async.port", SocketUtils.findAvailableServerSocket());
            }

            if (!nothrift && env.getProperty("thrift.port").equals("0")) {
                addProperties("thrift.port", SocketUtils.findAvailableServerSocket());
            }

        } catch (IOException e1) {
            throw new RuntimeException(e1);
        }

        if (env.getProperty("jgroups.multicast.bind_addr") != null) {
            System.setProperty("jgroups.multicast.bind_addr", env.getProperty("jgroups.multicast.bind_addr"));
        }

        if (env.getProperty("tbase.root") != null) {
            final MetaDataMapBuilder mdb = new MetaDataMapBuilder();

            for (String root : env.getProperty("tbase.root").split(",")) {
                mdb.build(root);
            }
        }

        context.register(annotatedClasses.toArray(new Class[annotatedClasses.size()]));

        if (isAsyncThriftEnabled()) {
            context.register(AsyncTcpThrift.class);
        }

        if (isThriftEnabled()) {
            context.register(TcpThrift.class);
        }

        if (isJGroupsEnabled()) {
            context.register(JGroups.class);
        } else {
            context.register(LoopbackJGroups.class);
        }

        if (isJmsEnabled()) {
            try {
                context.register(Class.forName("org.everthrift.jms.Jms"));
            } catch (ClassNotFoundException e) {
                throw Throwables.propagate(e);
            }
        } else {
            try {
                context.register(Class.forName("org.everthrift.jms.LoopbackJms"));
            } catch (ClassNotFoundException e) {
                log.warn("Cound't find LoopbackJms. @RpcJms Service will be disabled.");
            }
        }

        if (isRabbitEnabled()) {
            try {
                context.register(Class.forName("org.everthrift.rabbit.RabbitConfig"));
            } catch (ClassNotFoundException e) {
                throw Throwables.propagate(e);
            }
        } else {
            try {
                context.register(Class.forName("org.everthrift.rabbit.LocalRabbitConfig"));
            } catch (ClassNotFoundException e) {
                log.warn("Cound't find LocalRabbitConfig. @RpcRabbit Service will be disabled.");
            }
        }

        if (isJettyEnabled()) {
            try {
                context.register(Class.forName("org.everthrift.jetty.configs.Http"));
            } catch (ClassNotFoundException e) {
                throw Throwables.propagate(e);
            }
        }

        try {
            context.register(Class.forName("org.everthrift.cassandra.model.CassandraConfig"));
        } catch (ClassNotFoundException e) {
        }

        context.refresh();

        final List<String> propertySources = StreamSupport.stream(context.getEnvironment()
                                                                         .getPropertySources()
                                                                         .spliterator(), false)
                                                          .map(PropertySource::getName).collect(Collectors.toList());
        log.info("Used property sources:{}", propertySources);

        initialized = true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void runInContext(String xmlConfig, String className, String method) throws Exception {

        final Class<Callable> proc = (Class) Class.forName(className);

        final ClassPathXmlApplicationContext xmlContext = new ClassPathXmlApplicationContext(new String[]{xmlConfig}, false);
        xmlContext.registerShutdownHook();

        try {
            for (final PropertySource p : env.getPropertySources()) {
                xmlContext.getEnvironment().getPropertySources().addLast(p);
            }

            xmlContext.refresh();

            final Object processor = xmlContext.getBean(proc);
            if (processor == null) {
                throw new RuntimeException("Can't find Migration processor bean");
            }

            processor.getClass().getMethod(method).invoke(processor);
        } finally {
            if (xmlContext != null && xmlContext.isActive()) {
                xmlContext.close();
            }
        }
    }

    private void runSqlMigrator() throws Exception {
        log.info("Executing SQL migrations");

        runInContext("migration-context.xml", "org.everthrift.sql.migration.MigrationProcessor", "migrate");
    }

    private void runCMigrationProcessor() throws Exception {

        log.info("Executing cassandra migrations");

        runInContext("classpath:cassandra-migration-context.xml", "org.everthrift.cassandra.migrator.CMigrationProcessor", "migrate");
    }

    public synchronized void start() {
    }

    public synchronized void stop() {

        context.close();
    }

    /**
     * Add path to thrift controllers scan path
     *
     * @param p
     */
    public synchronized void addThriftScanPath(String p) {
        thriftScanPathList.add(p);
    }

    public synchronized List<String> getThriftScanPathList() {
        return thriftScanPathList;
    }

    private synchronized void addPropertySource(PropertySource<?> ps) {
        if (!initialized) {
            propertySourceList.add(ps);
        } else {
            env.getPropertySources().addFirst(ps);
        }
    }

    public synchronized boolean addPropertySource(String resourceName, boolean ignoreNotExisting) throws IOException {
        try {
            addPropertySource(new AppResourcePropertySource(resourceName));
            log.info("Added property source: {}", resourceName);
            return true;
        } catch (IOException e1) {
            if (ignoreNotExisting) {
                log.warn("Property source {} not found", resourceName);
                return false;
            } else {
                throw e1;
            }
        }
    }

    public void registerAnnotatedClasses(Class<?>... annotatedClasses) {
        for (Class cls : annotatedClasses) {
            this.annotatedClasses.add(cls);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public synchronized void addProperties(String name, Object... key_value) throws IOException {

        if (key_value.length % 2 != 0) {
            throw new IllegalArgumentException();
        }

        final Map props2 = new HashMap();

        for (int i = 0; i <= key_value.length - 2; i = i + 2) {

            if (!(key_value[i] instanceof String)) {
                throw new IllegalArgumentException(String.format("i=%d, type=%s", i, key_value[i].getClass()
                                                                                                 .toString()));
            }

            props2.put(key_value[i], key_value[i + 1]);
        }

        addPropertySource(new MapPropertySource(name, props2));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public synchronized void addProperty(String name, Object value) throws IOException {
        final Map props2 = new HashMap();
        props2.put(name, value);
        addPropertySource(new MapPropertySource(name, props2));
    }

    public void waitExit() {
        while (true) {

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }

        System.out.println("Exiting application...bye.");
        System.exit(0);
    }
}

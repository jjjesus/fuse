/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.mq.fabric;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.activemq.command.DiscoveryEvent;
import org.apache.activemq.transport.discovery.DiscoveryAgent;
import org.apache.activemq.transport.discovery.DiscoveryListener;
import org.codehaus.jackson.annotate.JsonProperty;
import org.fusesource.fabric.groups.ChangeListener;
import org.fusesource.fabric.groups.ClusteredSingleton;
import org.fusesource.fabric.groups.Group;
import org.fusesource.fabric.groups.NodeState;
import org.fusesource.fabric.groups.ZooKeeperGroupFactory;
import org.fusesource.fabric.zookeeper.IZKClient;
import org.fusesource.fabric.zookeeper.internal.ZKClient;
import org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils;
import org.linkedin.util.clock.Timespan;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FabricDiscoveryAgent implements DiscoveryAgent, ServiceTrackerCustomizer {
    
    private static final Logger LOG = LoggerFactory.getLogger(FabricDiscoveryAgent.class);

    private IZKClient zkClient;
    private boolean managedZkClient;

    private Group group;
    private String groupName = "default";

    private AtomicBoolean running=new AtomicBoolean();
    private final AtomicReference<DiscoveryListener> discoveryListener = new AtomicReference<DiscoveryListener>();

    private final HashMap<String, SimpleDiscoveryEvent> discoveredServices = new HashMap<String, SimpleDiscoveryEvent>();
    private final AtomicInteger startCounter = new AtomicInteger(0);

    private long initialReconnectDelay = 1000;
    private long maxReconnectDelay = 1000 * 30;
    private long backOffMultiplier = 2;
    private boolean useExponentialBackOff=true;    
    private int maxReconnectAttempts = 0;
    private final Object sleepMutex = new Object();
    private long minConnectTime = 5000;
    private String id;
    private String agent;

    BundleContext context;
    ServiceTracker tracker;

    List<String> services = new ArrayList<String>();

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    @Override
    public Object addingService(ServiceReference serviceReference) {
        zkClient = (IZKClient) context.getService(serviceReference);
        return zkClient;
    }

    @Override
    public void modifiedService(ServiceReference serviceReference, Object o) {
    }

    @Override
    public void removedService(ServiceReference serviceReference, Object o) {
    }

    static class ActiveMQNode implements NodeState {
        @JsonProperty
        String id;
        @JsonProperty
        String services[];
        @JsonProperty
        String agent;

        public String id() {
            return id;
        }
    }
    
    ActiveMQNode createState() {
        ActiveMQNode state = new ActiveMQNode();
        state.id = id;
        state.agent = agent;
        state.services = services.toArray(new String[services.size()]);
        return state;
    }
    
    ClusteredSingleton<ActiveMQNode> singleton = new ClusteredSingleton<ActiveMQNode>(ActiveMQNode.class);

    public FabricDiscoveryAgent() {
        if (FrameworkUtil.getBundle(getClass()) != null) {
            context = FrameworkUtil.getBundle(getClass()).getBundleContext();
            tracker = new ServiceTracker(context, IZKClient.class.getName(), this);
            tracker.open();
        }

        singleton.add(new ChangeListener(){
            @Override
            public void changed() {
                update(singleton.masters());
            }

            @Override
            public void connected() {
                changed();
            }
            public void disconnected() {
                changed();
            }
        });
    }


    class SimpleDiscoveryEvent extends DiscoveryEvent {

        private int connectFailures;
        private long reconnectDelay = initialReconnectDelay;
        private long connectTime = System.currentTimeMillis();
        private AtomicBoolean failed = new AtomicBoolean(false);
        private AtomicBoolean removed = new AtomicBoolean(false);

        public SimpleDiscoveryEvent(String service) {
            super(service);
        }

    }

    synchronized public void registerService(String service) throws IOException {
        services.add(service);
        updateClusterState();
    }

    public void updateClusterState() {
        if (startCounter.get() > 0 ) {
            if( id==null )
                throw new IllegalStateException("You must configure the id of the fabric discovery if you want to register services");
            singleton.update(createState());
        }
    }

    public void serviceFailed(DiscoveryEvent devent) throws IOException {

        final SimpleDiscoveryEvent event = (SimpleDiscoveryEvent)devent;
        if (event.failed.compareAndSet(false, true)) {
        	discoveryListener.get().onServiceRemove(event);
        	if(!event.removed.get()) {
	        	// Setup a thread to re-raise the event...
	            Thread thread = new Thread() {
	                public void run() {
	
	                    // We detect a failed connection attempt because the service
	                    // fails right away.
	                    if (event.connectTime + minConnectTime > System.currentTimeMillis()) {
	                        LOG.debug("Failure occurred soon after the discovery event was generated.  It will be classified as a connection failure: "+event);
	
	                        event.connectFailures++;
	
	                        if (maxReconnectAttempts > 0 && event.connectFailures >= maxReconnectAttempts) {
	                            LOG.debug("Reconnect attempts exceeded "+maxReconnectAttempts+" tries.  Reconnecting has been disabled.");
	                            return;
	                        }
	
	                        synchronized (sleepMutex) {
	                            try {
	                                if (!running.get() || event.removed.get()) {
	                                    return;
	                                }
	                                LOG.debug("Waiting "+event.reconnectDelay+" ms before attempting to reconnect.");
	                                sleepMutex.wait(event.reconnectDelay);
	                            } catch (InterruptedException ie) {
	                                Thread.currentThread().interrupt();
	                                return;
	                            }
	                        }
	
	                        if (!useExponentialBackOff) {
	                            event.reconnectDelay = initialReconnectDelay;
	                        } else {
	                            // Exponential increment of reconnect delay.
	                            event.reconnectDelay *= backOffMultiplier;
	                            if (event.reconnectDelay > maxReconnectDelay) {
	                                event.reconnectDelay = maxReconnectDelay;
	                            }
	                        }
	
	                    } else {
	                        event.connectFailures = 0;
	                        event.reconnectDelay = initialReconnectDelay;
	                    }
	
	                    if (!running.get() || event.removed.get()) {
	                        return;
	                    }
	
	                    event.connectTime = System.currentTimeMillis();
	                    event.failed.set(false);
	                    discoveryListener.get().onServiceAdd(event);
	                }
	            };
	            thread.setDaemon(true);
	            thread.start();
        	}
        }
    }

    public void setDiscoveryListener(DiscoveryListener discoveryListener) {
        this.discoveryListener.set(discoveryListener);
    }

    synchronized public void start() throws Exception {
        if( startCounter.addAndGet(1)==1 ) {
            running.set(true);

            if (zkClient == null) {
                LOG.info("Using local ZKClient");
                managedZkClient = true;
                ZKClient client = new ZKClient(System.getProperty("zookeeper.url", "localhost:2181"), Timespan.parse("10s"), null);
                client.setPassword(System.getProperty("zookeeper.password", "admin"));
                client.start();
                client.waitForConnected();
                zkClient = client;
            } else {
                managedZkClient = false;
            }

            group = ZooKeeperGroupFactory.create(zkClient, "/fabric/registry/clusters/fusemq/" + groupName);
            singleton.start(group);
            if( id!=null ) {
                singleton.join(createState());
            }
        }
    }

    synchronized  public void stop() throws Exception {
        if( startCounter.decrementAndGet()==0 ) {
            running.set(false);
            try {
                group.close();
            } catch (Throwable ignore) {
                // Most likely a ServiceUnavailableException: The Blueprint container is being or has been destroyed
            }
            if( managedZkClient ) {
                try {
                    zkClient.close();
                } catch (Throwable ignore) {
                    // Most likely a ServiceUnavailableException: The Blueprint container is being or has been destroyed
                }
                zkClient = null;
            }
        }
        if (tracker != null) {
            LOG.info("closing tracker");
            tracker.close();
        }
    }

    private void update(ActiveMQNode[] members) {

        // Find new registered services...
        DiscoveryListener discoveryListener = this.discoveryListener.get();
        if(discoveryListener!=null) {
            HashSet<String> activeServices = new HashSet<String>();
            for(ActiveMQNode m : members) {
                for(String service: m.services) {

                    String resolved = service;
                    try {
                        resolved = ZooKeeperUtils.getSubstitutedData(zkClient, service);
                    } catch (Exception e) {
                        // ignore, we'll use unresolved value
                    }
                    activeServices.add(resolved);
                }
            }
            // If there is error talking the the central server, then activeServices == null
            if( members !=null ) {
                synchronized(discoveredServices) {
                    
                    HashSet<String> removedServices = new HashSet<String>(discoveredServices.keySet());
                    removedServices.removeAll(activeServices);
                    
                    HashSet<String> addedServices = new HashSet<String>(activeServices);
                    addedServices.removeAll(discoveredServices.keySet());
                    addedServices.removeAll(removedServices);
                    
                    for (String service : addedServices) {
                        SimpleDiscoveryEvent e = new SimpleDiscoveryEvent(service);
                        discoveredServices.put(service, e);
                        discoveryListener.onServiceAdd(e);
                    }
                    
                    for (String service : removedServices) {
                    	SimpleDiscoveryEvent e = discoveredServices.remove(service);
                    	if( e !=null ) {
                    		e.removed.set(true);
                    	}
                        discoveryListener.onServiceRemove(e);
                    }
                }
            }
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public List<String> getServices() {
        return services;
    }

    public void setServices(String[] services) {
        this.services.clear();
        for(String s:services) {
            this.services.add(s);
        }
        updateClusterState();
    }

    public IZKClient getZkClient() {
        return zkClient;
    }

    public void setZkClient(IZKClient zkClient) {
        this.zkClient = zkClient;
    }

    public ClusteredSingleton<ActiveMQNode> getSingleton() {
        return singleton;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }
}

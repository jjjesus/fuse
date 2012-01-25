/*
 * Copyright (C) 2011, FuseSource Corp.  All rights reserved.
 * http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.fabric.itests.paxexam;

import java.io.IOException;
import java.util.Dictionary;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesService;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.ZooKeeperClusterService;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.linkedin.zookeeper.client.IZKClient;
import org.openengsb.labs.paxexam.karaf.options.LogLevelOption;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.ExamReactorStrategy;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.ops4j.pax.exam.options.extra.VMOption;
import org.ops4j.pax.exam.spi.reactors.AllConfinedStagedReactorFactory;
import org.osgi.service.cm.ConfigurationAdmin;


import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.debugConfiguration;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.logLevel;

@RunWith(JUnit4TestRunner.class)
@ExamReactorStrategy(AllConfinedStagedReactorFactory.class)
public class JaasRealmManagerTest extends FabricCommandsTestSupport {

    @Test
    public void testJaasRealmManager() throws Exception {
         //Wait for configAdmin service to become available.
        ConfigurationAdmin configAdmin = getOsgiService(ConfigurationAdmin.class);
        assertNotNull(configAdmin);
        FeaturesService featuresService = getOsgiService(FeaturesService.class);
        Feature feature = featuresService.getFeature("fabric-jaas");
        assertTrue(featuresService.isInstalled(feature));
        String sshRealm = readProperty(configAdmin,"org.apache.karaf.shell","sshRealm");
        String jmxRealm = readProperty(configAdmin,"org.apache.karaf.management","jmxRealm");
        assertEquals("karaf",sshRealm);
        assertEquals("karaf",jmxRealm);

        Thread.sleep(DEFAULT_WAIT);

        System.err.println(executeCommand("osgi:list"));
        System.err.println(executeCommand("fabric:ensemble-create root"));

        sshRealm = readProperty(configAdmin,"org.apache.karaf.shell","sshRealm");
        jmxRealm = readProperty(configAdmin,"org.apache.karaf.management","jmxRealm");
        assertEquals("zookeeper",sshRealm);
        assertEquals("zookeeper",jmxRealm);

        featuresService.uninstallFeature("fabric-jaas");
        Thread.sleep(DEFAULT_WAIT);

        sshRealm = readProperty(configAdmin,"org.apache.karaf.shell","sshRealm");
        jmxRealm = readProperty(configAdmin,"org.apache.karaf.management","jmxRealm");
        assertEquals("karaf",sshRealm);
        assertEquals("karaf",jmxRealm);
    }

    /**
     * Reads a property from {@code ConfigurationAdmin}.
     * @param configAdmin
     * @param pid          The confiugration PID.
     * @param propertyName The name of the property to read.
     * @return             The property value or null if pid or propertyName does not exist.
     */
   public String readProperty(ConfigurationAdmin configAdmin, String pid, String propertyName) throws IOException {
        String value = null;
            org.osgi.service.cm.Configuration config = configAdmin.getConfiguration(pid);
            if (config != null) {
                Dictionary dict = config.getProperties();
                if (dict != null) {
                    value = (String) dict.get(propertyName);
                }
            }
        return value;
    }

    @Configuration
    public Option[] config() {
        return new Option[]{
                fabricDistributionConfiguration(), keepRuntimeFolder(),
                logLevel(LogLevelOption.LogLevel.ERROR)};
    }
}
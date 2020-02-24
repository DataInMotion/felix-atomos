/*
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
package org.apache.felix.atomos.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.apache.felix.atomos.launch.AtomosLauncher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.connect.ConnectFrameworkFactory;
import org.osgi.framework.launch.Framework;

public class AtomosFrameworkFactoryTest
{

    private Framework testFramework;

    @AfterEach
    void afterTest() throws BundleException, InterruptedException, IOException
    {
        if (testFramework != null && testFramework.getState() == Bundle.ACTIVE)
        {
            testFramework.stop();
            testFramework.waitForStop(10000);
        }

    }

    @Test
    void testFactory(@TempDir Path storage) throws BundleException
    {
        ServiceLoader<ConnectFrameworkFactory> loader = ServiceLoader.load(
            getClass().getModule().getLayer(), ConnectFrameworkFactory.class);
        assertNotNull(loader, "null loader.");

        List<ConnectFrameworkFactory> factories = new ArrayList<>();
        loader.forEach((f) -> factories.add(f));
        assertFalse(factories.isEmpty(), "No factory found.");

        ConnectFrameworkFactory factory = factories.get(0);
        assertNotNull(factory, "null factory.");

        Map<String, String> config = Map.of(Constants.FRAMEWORK_STORAGE,
            storage.toFile().getAbsolutePath());
        testFramework = factory.newFramework(config,
            AtomosRuntime.newAtomosRuntime().getModuleConnector());
        doTestFramework(testFramework);
    }

    @Test
    void testRuntime(@TempDir Path storage) throws BundleException
    {
        AtomosRuntime runtime = AtomosRuntime.newAtomosRuntime();
        Map<String, String> config = Map.of(Constants.FRAMEWORK_STORAGE,
            storage.toFile().getAbsolutePath());
        testFramework = AtomosLauncher.newFramework(config, runtime);
        doTestFramework(testFramework);
    }

    private void doTestFramework(Framework testFramework) throws BundleException
    {
        testFramework.start();
        BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No context found.");
        Bundle[] bundles = bc.getBundles();

        assertEquals(ModuleLayer.boot().modules().size(), bundles.length,
            "Wrong number of bundles.");

        for (Bundle b : bundles)
        {
            String msg = b.getLocation() + ": " + b.getSymbolicName() + ": "
                + getState(b);
            System.out.println(msg);
            int expected;
            if ("osgi.annotation".equals(b.getSymbolicName()))
            {
                expected = Bundle.INSTALLED;
            }
            else
            {
                expected = Bundle.ACTIVE;
                if (b.getState() != expected)
                {
                    b.start();
                }
            }
            assertEquals(expected, b.getState(), "Wrong bundle state for bundle: " + msg);
        }
        Bundle javaLang = FrameworkUtil.getBundle(String.class);
        assertNotNull(javaLang, "No bundle found.");
        assertEquals(String.class.getModule().getName(), javaLang.getSymbolicName(),
            "Wrong bundle name.");
    }

    @Test
    void testConnectDisconnect(@TempDir Path storage) throws BundleException
    {
        AtomosRuntime runtime = AtomosRuntime.newAtomosRuntime();
        Map<String, String> config = Map.of( //
            Constants.FRAMEWORK_STORAGE, storage.toFile().getAbsolutePath(),
            AtomosRuntime.ATOMOS_CONTENT_INSTALL, "false");
        testFramework = AtomosLauncher.newFramework(config, runtime);
        testFramework.start();
        BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No BundleContext found.");

        assertEquals(1, bc.getBundles().length, "Wrong number of bundles.");

        AtomosContent systemContent = runtime.getBootLayer().findAtomosContent(
            bc.getBundle().getSymbolicName()).get();
        try
        {
            systemContent.disconnect();
            fail("Expected to fail disconnect of system bundle.");
        }
        catch (UnsupportedOperationException e)
        {
            // expected
        }

        assertEquals(Constants.SYSTEM_BUNDLE_LOCATION,
            systemContent.getConnectLocation());
        assertEquals(bc.getBundle(), systemContent.getBundle());
        assertEquals(systemContent,
            runtime.getConnectedContent(Constants.SYSTEM_BUNDLE_LOCATION),
            "Wrong system content.");

        AtomosContent javaBase = runtime.getBootLayer().findAtomosContent(
            "java.base").get();
        AtomosContent javaXML = runtime.getBootLayer().findAtomosContent(
            "java.xml").get();

        connect(runtime, javaBase, bc);
        connect(runtime, javaXML, bc);

        failConnect(javaBase, javaXML, bc, runtime);
    }

    private void failConnect(AtomosContent c1, AtomosContent c2, BundleContext bc,
        AtomosRuntime runtime) throws BundleException
    {
        Bundle b1 = c1.getBundle();
        try
        {
            c1.connect("should.fail");
            fail("Expected failure to connect");
        }
        catch (IllegalStateException e)
        {
            // expected
        }
        c1.disconnect();
        assertNull(c1.getConnectLocation(), "Unexpected connect location.");
        b1.uninstall();

        try
        {
            c1.connect(c2.getConnectLocation());
            fail("Expected failure to connect");
        }
        catch (IllegalStateException e)
        {
            // expected
        }
        connect(runtime, c1, bc);
        b1 = c1.getBundle();
        // connect again with same location and install should be a no-op
        c1.connect(c1.getConnectLocation());
        Bundle reinstall = bc.installBundle(b1.getLocation());
        assertEquals(b1, reinstall, "Wrong bundle.");
    }

    private void connect(AtomosRuntime runtime, AtomosContent content, BundleContext bc)
        throws BundleException
    {
        assertNull(content.getConnectLocation(), "Unexpected connect location.");
        assertNull(content.getBundle(), "Unexpected bundle.");
        content.connect(content.getSymbolicName());
        assertEquals(content.getSymbolicName(), content.getConnectLocation(),
            "Unexpected connect location.");
        Bundle b = bc.installBundle(content.getConnectLocation());
        assertEquals(content.getConnectLocation(), b.getLocation(),
            "Unexpected bundle location.");
        assertEquals(b, content.getBundle(), "Wrong bundle.");
        assertEquals(content, runtime.getConnectedContent(b.getLocation()));
    }

    @Test
    void testInstallPrefix(@TempDir Path storage) throws BundleException
    {
        AtomosRuntime runtime = AtomosRuntime.newAtomosRuntime();
        Map<String, String> config = Map.of( //
            Constants.FRAMEWORK_STORAGE, storage.toFile().getAbsolutePath(),
            AtomosRuntime.ATOMOS_CONTENT_INSTALL, "false");
        testFramework = AtomosLauncher.newFramework(config, runtime);
        testFramework.start();
        BundleContext bc = testFramework.getBundleContext();
        assertNotNull(bc, "No BundleContext found.");

        assertEquals(1, bc.getBundles().length, "Wrong number of bundles.");

        AtomosContent javaBase = runtime.getBootLayer().findAtomosContent(
            "java.base").get();
        AtomosContent javaXML = runtime.getBootLayer().findAtomosContent(
            "java.xml").get();

        install(runtime, javaBase, bc);
        install(runtime, javaXML, bc);

        failInstall(javaBase, javaXML, bc, runtime);
    }

    private void install(AtomosRuntime runtime, AtomosContent content, BundleContext bc)
        throws BundleException
    {
        Bundle b = content.install("test");
        assertEquals(b, content.getBundle(), "Wrong bundle.");
        assertEquals(content, runtime.getConnectedContent(b.getLocation()),
            "Wrong content.");
        assertEquals(b.getLocation(), content.getConnectLocation());
        assertTrue(b.getLocation().startsWith("test:"),
            "Wrong location: " + b.getLocation());
        Bundle reinstall = content.install("test");
        assertEquals(b, reinstall, "Wrong bundle.");
    }

    private void failInstall(AtomosContent c1, AtomosContent c2, BundleContext bc,
        AtomosRuntime runtime) throws BundleException
    {
        Bundle b1 = c1.getBundle();
        String b1Location = b1.getLocation();
        b1.uninstall();
        c1.disconnect();

        Bundle b2 = c2.getBundle();
        b2.uninstall();
        c2.disconnect();
        c2.connect(b1Location);
        b2 = bc.installBundle(b1Location);

        try {
            c1.install("test");
            fail("Expected failure to install using a connected location");
        }
        catch (IllegalStateException e)
        {
            // expected
        }

    }

    private String getState(Bundle b)
    {
        switch (b.getState())
        {
            case Bundle.UNINSTALLED:
                return "UNINSTALLED";
            case Bundle.INSTALLED:
                return "INSTALLED";
            case Bundle.RESOLVED:
                return "RESOLVED";
            case Bundle.STARTING:
                return "STARTING";
            case Bundle.ACTIVE:
                return "ACTIVE";
            case Bundle.STOPPING:
                return "STOPPING";
            default:
                return "unknown";
        }
    }

}

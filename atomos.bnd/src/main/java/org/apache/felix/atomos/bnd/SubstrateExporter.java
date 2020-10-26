package org.apache.felix.atomos.bnd;

import java.io.File;
import java.nio.file.Path;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.print.attribute.standard.PDLOverrideSupported;

import java.util.Optional;
import java.util.UUID;

import org.apache.felix.atomos.utils.api.Config;
import org.apache.felix.atomos.utils.api.Context;
import org.apache.felix.atomos.utils.api.FileType;
import org.apache.felix.atomos.utils.api.Launcher;
import org.apache.felix.atomos.utils.api.LauncherBuilder;
import org.apache.felix.atomos.utils.core.plugins.ComponentDescriptionPlugin;
import org.apache.felix.atomos.utils.core.plugins.GogoPlugin;
import org.apache.felix.atomos.utils.core.plugins.OsgiDTOPlugin;
import org.apache.felix.atomos.utils.core.plugins.ResourcePlugin;
import org.apache.felix.atomos.utils.core.plugins.activator.InvocatingBundleActivatorPlugin;
import org.apache.felix.atomos.utils.core.plugins.activator.ReflectionBundleActivatorPlugin;
import org.apache.felix.atomos.utils.core.plugins.collector.PathCollectorPlugin;
import org.apache.felix.atomos.utils.core.plugins.collector.PathCollectorPluginConfig;
import org.apache.felix.atomos.utils.core.plugins.finaliser.ni.NativeImageBuilderConfig;
import org.apache.felix.atomos.utils.core.plugins.finaliser.ni.NativeImagePlugin;
import org.apache.felix.atomos.utils.core.plugins.index.IndexOutputType;
import org.apache.felix.atomos.utils.core.plugins.index.IndexPlugin;
import org.apache.felix.atomos.utils.core.plugins.index.IndexPluginConfig;

import aQute.bnd.annotation.plugin.BndPlugin;
import aQute.bnd.build.Container;
import aQute.bnd.build.Project;
import aQute.bnd.osgi.Resource;
import aQute.bnd.service.Strategy;
import aQute.bnd.service.export.Exporter;
import aQute.bnd.service.externalplugin.ExternalPlugin;
import aQute.lib.io.IO;

@BndPlugin(name = "SubstrateExporter")
@ExternalPlugin(name = "SubstrateExporter", objectClass = Exporter.class)
public class SubstrateExporter implements Exporter{

	private static final String GRAALVM_HOME = "GRAALVM_HOME";
	private static final String GRAAL_VM = "GRAAL_VM";
	private static final String NATIVE_IMAGE_EXECUTABLE = "native.image.executable";
	private static final String SUBSTRATE = "substrate";

	@Override
	public String[] getTypes() {
		return new String[]{SUBSTRATE};
	}

	@Override
	public Entry<String, Resource> export(String type, Project project, Map<String, String> options) throws Exception {
		
		String nativeImageExecutable = options.get(NATIVE_IMAGE_EXECUTABLE);
		if(nativeImageExecutable == null) {
			if(null == System.getenv(GRAALVM_HOME) && null == System.getenv(GRAAL_VM) ){
				project.error("Neither environment property %s or %s nor plugin property %s is set. This is mandatory configuration in order to get the exporter to work", GRAALVM_HOME, GRAAL_VM, NATIVE_IMAGE_EXECUTABLE);
			}
		}
		
		Container atomosRuntime = project.getBundle("org.apache.felix.atomos.runtime", "latest", Strategy.HIGHEST, Collections.emptyMap());
		if(atomosRuntime == null) {
			project.error("No Bundle with BSN [org.apache.felix.atomos.runtime] could be found in any of the available repositories");
		}
		
    	File outputDirectory = new File(project.getTarget(), UUID.randomUUID().toString());
    	if(outputDirectory.exists()) {
    		System.err.println(outputDirectory + " exists, deleting");
    		IO.delete(outputDirectory);
    		System.err.println(outputDirectory + ": exists? " + outputDirectory.exists());
    	}
    	
    	IO.mkdirs(outputDirectory);
    	outputDirectory.deleteOnExit();
    	LauncherBuilder builder = Launcher.builder();

        File runtime = new File(outputDirectory, "runtime.jar");
        IO.copy(getClass().getResourceAsStream("/runtime.jar"), runtime);
        
        PathCollectorPluginConfig pc = new PathCollectorPluginConfig()
        {
            @Override
            public FileType fileType()
            {
                return FileType.ARTIFACT;
            }

            @Override
            public List<String> filters()
            {
                return null;
            }

            @Override
            public List<Path> paths()
            {
                try {
                	List<Path> paths = new LinkedList<Path>();
                	project.getRunbundles().stream().map(Container::getFile).map(File::toPath).forEach(paths::add);
                	project.getRunpath().stream().map(Container::getFile).map(File::toPath).forEach(paths::add);
                	project.getRunFw().stream().map(Container::getFile).map(File::toPath).forEach(paths::add);
                	paths.add(runtime.toPath());
					return paths;
                } catch (Exception e) {
                	project.error("Could not parse runbundles", e);
                	return Collections.emptyList();
				}
            }
        };
        
    	//Collect files using paths and filters
        builder.addPlugin(PathCollectorPlugin.class, pc);
    	
        IndexPluginConfig ic = new IndexPluginConfig()
        {
            @Override
            public Path indexOutputDirectory()
            {
                return outputDirectory.toPath();
            }

            @Override
            public IndexOutputType indexOutputType()
            {
                return IndexOutputType.DIRECTORY;
            }
        };
        
        builder.addPlugin(IndexPlugin.class, ic);
        
        //
        Config cfg = new Config()
        {
        };
        builder//
            .addPlugin(ReflectionBundleActivatorPlugin.class, cfg)//
            .addPlugin(ComponentDescriptionPlugin.class, cfg)//
            .addPlugin(GogoPlugin.class, cfg)//
            .addPlugin(InvocatingBundleActivatorPlugin.class, cfg)//
            .addPlugin(OsgiDTOPlugin.class, cfg)//
            .addPlugin(ResourcePlugin.class, cfg);//

        
        NativeImageBuilderConfig nic = new NativeImageBuilderConfig() {
			
			@Override
			public List<Path> resourceConfigurationFiles() {
				return Collections.emptyList();
			}
			
			@Override
			public List<Path> reflectionConfigurationFiles() {
				return Collections.emptyList();
			}
			
			@Override
			public Boolean noFallback() {
				return Boolean.TRUE;
			}
			
			@Override
			public Map<String, String> nativeImageVmSystemProperties() {
				return project.getRunProperties();
			}
			
			@Override
			public List<String> nativeImageVmFlags() {
				return new ArrayList(project.getRunVM());
			}
			
			@Override
			public Path nativeImageOutputDirectory() {
				return outputDirectory.toPath();
			}
			
			@Override
			public String nativeImageMainClass() {
				return "org.apache.felix.atomos.launch.AtomosLauncher";
			}
			
			@Override
			public Path nativeImageExecutable() {
				if(nativeImageExecutable != null ) {
					return new File(nativeImageExecutable).toPath();
				} else {
					return null;
				}
			}
			
			@Override
			public String nativeImageApplicationName() {
				return project.getName();
			}
			
			@Override
			public List<String> nativeImageAdditionalInitializeAtBuildTime() {
				return Collections.emptyList();
			}
			
			@Override
			public List<Path> dynamicProxyConfigurationFiles() {
				return Collections.emptyList();
			}
		};
        
        builder.addPlugin(NativeImagePlugin.class, nic);

        System.err.println("Running Context");
        
	    Context context = builder.build().execute();
	    System.err.println("Finished Context");
	    Optional<Path> files = context.getFiles(FileType.NATIVE_IMAGE_BINARY).findFirst();
	    
	    files.ifPresent(f -> System.err.println("Finished with result " + f));
	    
	    Path path = files.get();
	    
	    return new SimpleEntry<String, Resource>(project.getName(), Resource.fromURL(path.toFile().toURI().toURL()));

	}

}

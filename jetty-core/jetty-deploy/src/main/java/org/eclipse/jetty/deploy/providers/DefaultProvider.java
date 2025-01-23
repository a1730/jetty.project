//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.deploy.providers;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathCollators;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Default Jetty Environment WebApp Hot Deployment Provider.</p>
 *
 * <p>This provider scans one or more directories (typically "webapps") for contexts to
 * deploy, which may be:</p>
 * <ul>
 * <li>A standard WAR file (must end in ".war")</li>
 * <li>A directory containing an expanded WAR file</li>
 * <li>A directory containing static content</li>
 * <li>An XML descriptor in {@link XmlConfiguration} format that configures a {@link ContextHandler} instance</li>
 * </ul>
 * <p>To avoid double deployments and allow flexibility of the content of the scanned directories, the provider
 * implements some heuristics to ignore some files found in the scans:
 * </p>
 * <ul>
 * <li>Hidden files (starting with {@code "."}) are ignored</li>
 * <li>Directories with names ending in {@code ".d"} are ignored</li>
 * <li>Property files with names ending in {@code ".properties"} are not deployed.</li>
 * <li>If a directory and a WAR file exist (eg: {@code foo/} and {@code foo.war}) then the directory is assumed to be
 * the unpacked WAR and only the WAR file is deployed (which may reuse the unpacked directory)</li>
 * <li>If a directory and a matching XML file exist (eg: {@code foo/} and {@code foo.xml}) then the directory is assumed to be
 * an unpacked WAR and only the XML file is deployed (which may use the directory in its configuration)</li>
 * <li>If a WAR file and a matching XML file exist (eg: {@code foo.war} and {@code foo.xml}) then the WAR file is assumed to
 * be configured by the XML file and only the XML file is deployed.
 * </ul>
 * <p>For XML configured contexts, the following is available.</p>
 * <ul>
 * <li>The XML Object ID Map will have a reference to the {@link Server} instance via the ID name {@code "Server"}</li>
 * <li>The Default XML Properties are populated from a call to {@link XmlConfiguration#setJettyStandardIdsAndProperties(Object, Path)} (for things like {@code jetty.home} and {@code jetty.base})</li>
 * <li>An extra XML Property named {@code "jetty.webapps"} is available, and points to the monitored path.</li>
 * </ul>
 * <p>
 * Context Deployment properties will be initialized with:
 * </p>
 * <ul>
 * <li>The properties set on the application via embedded calls modifying {@link DefaultApp#getAttributes()}</li>
 * <li>The app specific properties file {@code webapps/<webapp-name>.properties}</li>
 * <li>The environment specific properties file {@code webapps/<environment-name>[-zzz].properties}</li>
 * <li>The {@link Attributes} from the {@link Environment}</li>
 * </ul>
 *
 * <p>
 * To configure Environment specific deployment {@link Attributes},
 * either set the appropriate {@link Deployable} attribute via {@link Attributes#setAttribute(String, Object)},
 * or use the convenience class {@link EnvironmentConfig}.
 * </p>
 *
 * <pre>{@code
 * DefaultDeploymentAppProvider provider = new DefaultDeploymentAppProvider();
 * EnvironmentConfig env10config = provider.configureEnvironment("ee10");
 * env10config.setExtractWars(true);
 * env10config.setParentLoaderPriority(false);
 * }</pre>
 */
@ManagedObject("Provider for start-up deployment of webapps based on presence in directory")
public class DefaultProvider extends ContainerLifeCycle implements AppProvider, Scanner.ChangeSetListener
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultProvider.class);

    private final FilenameFilter filenameFilter;
    private final List<Path> monitoredDirs = new CopyOnWriteArrayList<>();

    private Map<String, DefaultApp> apps = new HashMap<>();
    private DeploymentManager deploymentManager;
    private Comparator<DeployAction> actionComparator = new DeployActionComparator();
    private DefaultContextHandlerFactory contextHandlerFactory = new DefaultContextHandlerFactory();
    private Path environmentsDir;
    private int scanInterval = 10;
    private Scanner scanner;
    private boolean useRealPaths;
    private boolean deferInitialScan = false;

    public DefaultProvider()
    {
        this(new MonitoredPathFilter());
    }

    public DefaultProvider(FilenameFilter filter)
    {
        filenameFilter = filter;
        setScanInterval(0);
    }

    private static String asStringList(Collection<Path> paths)
    {
        return paths.stream()
            .sorted(PathCollators.byName(true))
            .map(Path::toString)
            .collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * @param dir Directory to scan for deployable artifacts
     */
    public void addMonitoredDirectory(Path dir)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Adding monitored directory: {}", dir);
        if (isStarted())
            throw new IllegalStateException("Unable to add monitored directory while running");
        monitoredDirs.add(Objects.requireNonNull(dir));
    }

    public void addScannerListener(Scanner.Listener listener)
    {
        scanner.addListener(listener);
    }

    /**
     * Configure the Environment specific Deploy settings.
     *
     * @param name the name of the environment.
     * @return the deployment configuration for the {@link Environment}.
     */
    public EnvironmentConfig configureEnvironment(String name)
    {
        return new EnvironmentConfig(Environment.ensure(name));
    }

    public Comparator<DeployAction> getActionComparator()
    {
        return actionComparator;
    }

    public void setActionComparator(Comparator<DeployAction> actionComparator)
    {
        this.actionComparator = actionComparator;
    }

    public Collection<DefaultApp> getApps()
    {
        return apps.values();
    }

    public DefaultContextHandlerFactory getContextHandlerFactory()
    {
        return contextHandlerFactory;
    }

    public void setContextHandlerFactory(DefaultContextHandlerFactory contextHandlerFactory)
    {
        this.contextHandlerFactory = contextHandlerFactory;
    }

    /**
     * Get the default {@link Environment} name for discovered web applications that
     * do not declare the {@link Environment} that they belong to.
     *
     * <p>
     * Falls back to {@link Environment#getAll()} list, and returns
     * the first name returned after sorting with {@link Deployable#ENVIRONMENT_COMPARATOR}
     * </p>
     *
     * @return the default environment name.
     */
    public String getDefaultEnvironmentName()
    {
        return this.contextHandlerFactory.getDefaultEnvironmentName();
    }

    public void setDefaultEnvironmentName(String name)
    {
        this.contextHandlerFactory.setDefaultEnvironmentName(name);
    }

    /**
     * Get the deploymentManager.
     *
     * @return the deploymentManager
     */
    public DeploymentManager getDeploymentManager()
    {
        return deploymentManager;
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        this.deploymentManager = deploymentManager;
    }

    public Path getEnvironmentsDirectory()
    {
        return environmentsDir;
    }

    public void setEnvironmentsDirectory(Path dir)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Setting Environments directory: {}", dir);
        if (isStarted())
            throw new IllegalStateException("Unable to add environments directory while running");
        environmentsDir = dir;
    }

    public List<Path> getMonitoredDirectories()
    {
        return monitoredDirs;
    }

    public void setMonitoredDirectories(Collection<Path> directories)
    {
        if (isStarted())
            throw new IllegalStateException("Unable to add monitored directories while running");

        monitoredDirs.clear();

        for (Path dir : directories)
        {
            addMonitoredDirectory(dir);
        }
    }

    @ManagedAttribute("scanning interval to detect changes which need reloaded")
    public int getScanInterval()
    {
        return scanInterval;
    }

    public void setScanInterval(int scanInterval)
    {
        this.scanInterval = scanInterval;
    }

    public Comparator<DeployAction> getUnitComparator()
    {
        return actionComparator;
    }

    public void setUnitComparator(Comparator<DeployAction> comparator)
    {
        this.actionComparator = comparator;
    }

    /**
     * Test if initial scan should be deferred.
     *
     * @return true if initial scan is deferred, false to have initial scan occur on startup of ScanningAppProvider.
     */
    public boolean isDeferInitialScan()
    {
        return deferInitialScan;
    }

    /**
     * Flag to control initial scan behavior.
     *
     * <ul>
     *     <li>{@code true} - to have initial scan deferred until the {@link Server} component
     *     has reached it's STARTED state.<br>
     *     Note: any failures in a deployment will not fail the Server startup in this mode.</li>
     *     <li>{@code false} - (default value) to have initial scan occur as normal on
     *     ScanningAppProvider startup.</li>
     * </ul>
     *
     * @param defer true to defer initial scan, false to have initial scan occur on startup of ScanningAppProvider.
     */
    public void setDeferInitialScan(boolean defer)
    {
        deferInitialScan = defer;
    }

    /**
     * @return True if the real path of the scanned files should be used for deployment.
     */
    public boolean isUseRealPaths()
    {
        return useRealPaths;
    }

    /**
     * @param useRealPaths True if the real path of the scanned files should be used for deployment.
     */
    public void setUseRealPaths(boolean useRealPaths)
    {
        this.useRealPaths = useRealPaths;
    }

    /**
     * This is the listener event for Scanner to report changes.
     *
     * @param changeSet the changeset from the Scanner.
     */
    @Override
    public void pathsChanged(Map<Path, Scanner.Notification> changeSet)
    {
        Objects.requireNonNull(changeSet);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("pathsChanged: {}",
                changeSet.entrySet()
                    .stream()
                    .map((e) -> String.format("%s|%s", e.getKey(), e.getValue()))
                    .collect(Collectors.joining(", ", "[", "]"))
            );
        }

        Set<String> changedBaseNames = new HashSet<>();
        Set<String> availableEnvironmentNames = Environment.getAll().stream()
            .map(Environment::getName).collect(Collectors.toUnmodifiableSet());
        Set<String> changedEnvironments = new HashSet<>();

        for (Map.Entry<Path, Scanner.Notification> entry : changeSet.entrySet())
        {
            Path path = entry.getKey();
            DefaultApp.State state = switch (entry.getValue())
            {
                case ADDED ->
                {
                    yield DefaultApp.State.ADDED;
                }
                case CHANGED ->
                {
                    yield DefaultApp.State.CHANGED;
                }
                case REMOVED ->
                {
                    yield DefaultApp.State.REMOVED;
                }
            };

            // Using lower-case as defined by System Locale, as the files themselves from System FS.
            String basename = FileID.getBasename(path).toLowerCase();

            if (isMonitoredPath(path))
            {
                // we have a normal path entry
                changedBaseNames.add(basename);
                DefaultApp app = apps.computeIfAbsent(basename, DefaultApp::new);
                app.putPath(path, state);
            }
            else if (isEnvironmentConfigPath(path))
            {
                String envname = null;
                for (String name : availableEnvironmentNames)
                {
                    if (basename.startsWith(name))
                        envname = name;
                }
                if (StringUtil.isBlank(envname))
                {
                    LOG.warn("Unable to determine Environment for file: {}", path);
                    continue;
                }
                changedEnvironments.add(envname);
            }
        }

        // Now we know the DefaultApp instances that are changed by the incoming
        // Scanner changes alone.

        List<DefaultApp> changedApps = changedBaseNames
            .stream()
            .map(name -> apps.get(name))
            .collect(Collectors.toList());

        if (changedEnvironments.isEmpty())
        {
            // We have a set of changes, with no environment configuration
            // changes present.
            List<DeployAction> actions = buildActionList(changedApps);
            performActions(actions);
        }
        else
        {
            // We have incoming environment configuration changes
            // We need to add any missing DefaultApp that have changed
            // due to incoming environment configuration changes.

            for (String changedEnvName : changedEnvironments)
            {
                // Add any missing apps to changedApps list
                for (DefaultApp app : apps.values())
                {
                    if (changedBaseNames.contains(app.getName()))
                        continue; // skip app that's already in the change list.

                    if (changedEnvName.equalsIgnoreCase(app.getEnvironmentName()))
                    {
                        if (app.getState() == DefaultApp.State.UNCHANGED)
                            app.setState(DefaultApp.State.CHANGED);
                        changedApps.add(app);
                        changedBaseNames.add(app.getName());
                    }
                }

                // Load any Environment properties files into Environment attributes.
                try
                {
                    Properties envProps = loadEnvironmentProperties(changedEnvName);
                    Environment environment = Environment.get(changedEnvName);
                    envProps.stringPropertyNames().forEach(
                        k -> environment.setAttribute(k, envProps.getProperty(k))
                    );
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unable to load environment properties for environment [{}]", changedEnvName, e);
                }
            }

            List<DeployAction> actions = buildActionList(changedApps);
            performActions(actions);
        }
    }

    @ManagedOperation(value = "Scan the monitored directories", impact = "ACTION")
    public void scan()
    {
        LOG.info("Performing scan of monitored directories: {}",
            monitoredDirs.stream()
                .map(Path::toUri)
                .map(URI::toASCIIString)
                .collect(Collectors.joining(", ", "[", "]"))
        );
        scanner.nudge();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[dirs=%s]", this.getClass(), hashCode(), monitoredDirs);
    }

    protected List<DeployAction> buildActionList(List<DefaultApp> changedApps)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("buildActionList: {}", changedApps);

        List<DeployAction> actions = new ArrayList<>();
        for (DefaultApp app : changedApps)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("changed app: {}", app);

            switch (app.getState())
            {
                case ADDED ->
                {
                    actions.add(new DeployAction(DeployAction.Type.ADD, app));
                }
                case CHANGED ->
                {
                    actions.add(new DeployAction(DeployAction.Type.REMOVE, app));
                    actions.add(new DeployAction(DeployAction.Type.ADD, app));
                }
                case REMOVED ->
                {
                    actions.add(new DeployAction(DeployAction.Type.REMOVE, app));
                }
            }
        }
        return sortActions(actions);
    }

    @Override
    protected void doStart() throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} doStart()", this);
        if (monitoredDirs.isEmpty())
            throw new IllegalStateException("No monitored dir specified");

        LOG.info("Deployment monitor in {} at intervals {}s", monitoredDirs, getScanInterval());

        Predicate<Path> validDir = (path) ->
        {
            if (!Files.exists(path))
            {
                LOG.warn("Does not exist: {}", path);
                return false;
            }

            if (!Files.isDirectory(path))
            {
                LOG.warn("Is not a directory: {}", path);
                return false;
            }

            return true;
        };

        List<Path> dirs = new ArrayList<>();
        for (Path dir : monitoredDirs)
        {
            if (validDir.test(dir))
                dirs.add(dir);
        }

        if (environmentsDir != null)
        {
            if (validDir.test(environmentsDir))
                dirs.add(environmentsDir);
        }

        scanner = new Scanner(null, useRealPaths);
        scanner.setScanDirs(dirs);
        scanner.setScanInterval(scanInterval);
        scanner.setFilenameFilter(filenameFilter);
        scanner.setReportDirs(true);
        scanner.setScanDepth(1);
        scanner.addListener(this);
        scanner.setReportExistingFilesOnStartup(true);
        scanner.setAutoStartScanning(!deferInitialScan);
        addBean(scanner);

        if (isDeferInitialScan())
        {
            // Setup listener to wait for Server in STARTED state, which
            // triggers the first scan of the monitored directories
            getDeploymentManager().getServer().addEventListener(
                new LifeCycle.Listener()
                {
                    @Override
                    public void lifeCycleStarted(LifeCycle event)
                    {
                        if (event instanceof Server)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Triggering Deferred Scan of {}", dirs);
                            scanner.startScanning();
                        }
                    }
                });
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        if (scanner != null)
        {
            removeBean(scanner);
            scanner.removeListener(this);
            scanner = null;
        }
    }

    protected boolean exists(String path)
    {
        return scanner.exists(path);
    }

    protected List<DefaultApp> getAppsInEnvironment(String envName)
    {
        return apps.values().stream()
            .filter((app) -> envName.equals(app.getEnvironmentName()))
            .toList();
    }

    protected boolean isEnvironmentConfigPath(Path path)
    {
        if (environmentsDir == null)
            return false;

        return isSameDir(environmentsDir, path.getParent());
    }

    protected boolean isMonitoredPath(Path path)
    {
        Path parentDir = path.getParent();
        for (Path monitoredDir : monitoredDirs)
        {
            if (isSameDir(monitoredDir, parentDir))
                return true;
        }
        return false;
    }

    protected boolean isSameDir(Path dirA, Path dirB)
    {
        try
        {
            return Files.isSameFile(dirA, dirB);
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring: Unable to use Files.isSameFile({}, {})", dirA, dirB, e);
            return false;
        }
    }

    protected void performActions(List<DeployAction> actions)
    {
        for (DeployAction step : actions)
        {
            try
            {
                switch (step.getType())
                {
                    case REMOVE ->
                    {
                        apps.remove(step.getName());
                        deploymentManager.removeApp(step.getApp());
                    }
                    case ADD ->
                    {
                        // Load <basename>.properties into app.
                        step.getApp().loadProperties();

                        // Ensure Environment name is set
                        String appEnvironment = step.getApp().getEnvironmentName();
                        if (StringUtil.isBlank(appEnvironment))
                            appEnvironment = getDefaultEnvironmentName();
                        step.getApp().setEnvironmentName(appEnvironment);

                        // Create a new Attributes for the App, which is the
                        // combination of Environment Attributes with App Attributes overlaying them.
                        Environment environment = Environment.get(appEnvironment);
                        Attributes deployAttributes = initAttributes(environment, step.getApp());

                        // Ensure that Environment configuration XMLs are listed in deployAttributes
                        List<Path> envXmlPaths = findEnvironmentXmlPaths(deployAttributes);
                        envXmlPaths.sort(PathCollators.byName(true));
                        DefaultContextHandlerFactory.setEnvironmentXmlPaths(deployAttributes, envXmlPaths);

                        // Create the Context Handler
                        ContextHandler contextHandler = getContextHandlerFactory().newContextHandler(this, step.getApp(), deployAttributes);
                        step.getApp().setContextHandler(contextHandler);

                        // Introduce the App to the DeploymentManager
                        deploymentManager.addApp(step.getApp());
                    }
                }
            }
            catch (Throwable t)
            {
                LOG.warn("Failed to to perform action {} on {}", step.getType(), step.getApp(), t);
            }
            finally
            {
                step.getApp().resetStates();
            }
        }
    }

    protected List<DeployAction> sortActions(List<DeployAction> actions)
    {
        Comparator<DeployAction> deployActionComparator = getActionComparator();
        if (deployActionComparator != null)
            actions.sort(deployActionComparator);
        return actions;
    }

    private void copyAttributes(Attributes sourceAttributes, Attributes destAttributes)
    {
        sourceAttributes.getAttributeNameSet().forEach((key) ->
            destAttributes.setAttribute(key, sourceAttributes.getAttribute(key)));
    }

    private List<Path> findEnvironmentXmlPaths(Attributes deployAttributes)
    {
        List<Path> rawEnvXmlPaths = deployAttributes.getAttributeNameSet()
            .stream()
            .filter(k -> k.startsWith(Deployable.ENVIRONMENT_XML))
            .map(k -> Path.of((String)deployAttributes.getAttribute(k)))
            .toList();

        List<Path> ret = new ArrayList<>();
        for (Path rawPath : rawEnvXmlPaths)
        {
            if (Files.exists(rawPath))
            {
                if (Files.isRegularFile(rawPath))
                {
                    // just add it, nothing else to do.
                    if (rawPath.isAbsolute())
                        ret.add(rawPath);
                    else
                        ret.add(rawPath.toAbsolutePath());
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Ignoring non-file reference to environment xml: {}", rawPath);
                }
            }
            else if (!rawPath.isAbsolute())
            {
                // we have a relative defined path, try to resolve it from known locations
                if (LOG.isDebugEnabled())
                    LOG.debug("Resolving environment xml path relative reference: {}", rawPath);
                boolean found = false;
                for (Path monitoredDir : getMonitoredDirectories())
                {
                    Path resolved = monitoredDir.resolve(rawPath);
                    if (Files.isRegularFile(resolved))
                    {
                        found = true;
                        // add resolved path
                        ret.add(resolved);
                    }
                    else
                    {
                        // try resolve from parent (this is backward compatible with 12.0.0)
                        resolved = monitoredDir.getParent().resolve(rawPath);
                        if (Files.isRegularFile(resolved))
                        {
                            found = true;
                            // add resolved path
                            ret.add(resolved);
                        }
                    }
                }
                if (!found && LOG.isDebugEnabled())
                    LOG.debug("Ignoring relative environment xml path that doesn't exist: {}", rawPath);
            }
        }

        return ret;
    }

    private Attributes initAttributes(Environment environment, DefaultApp app) throws IOException
    {
        Attributes attributes = new Attributes.Mapped();

        // Grab Environment attributes first
        copyAttributes(environment, attributes);

        // Overlay the app attributes
        copyAttributes(app.getAttributes(), attributes);

        // The now merged attributes
        return attributes;
    }

    /**
     * Load all of the {@link Environment} specific {@code <env-name>[-<name>].properties} files
     * found in the directory provided.
     *
     * <p>
     * All found properties files are first sorted by filename, then loaded one by one into
     * a single {@link Properties} instance.
     * </p>
     *
     * @param env the environment name
     */
    private Properties loadEnvironmentProperties(String env) throws IOException
    {
        Properties props = new Properties();

        Path dir = getEnvironmentsDirectory();
        if (dir == null)
        {
            // nothing to load
            return props;
        }

        if (!Files.isDirectory(dir))
        {
            LOG.warn("Not an environments directory: {}", dir);
            return props;
        }

        List<Path> envPropertyFiles;

        // Get all environment specific properties files for this environment,
        // order them according to the lexical ordering of the filenames
        try (Stream<Path> paths = Files.list(dir))
        {
            envPropertyFiles = paths.filter(Files::isRegularFile)
                .filter(p -> FileID.isExtension(p, "properties"))
                .filter(p ->
                {
                    String name = p.getFileName().toString();
                    return name.startsWith(env);
                })
                .sorted(PathCollators.byName(true))
                .toList();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Environment property files {}", envPropertyFiles);

        // Load each *.properties file
        for (Path file : envPropertyFiles)
        {
            try (InputStream stream = Files.newInputStream(file))
            {
                Properties tmp = new Properties();
                tmp.load(stream);
                //put each property into our substitution pool
                tmp.stringPropertyNames().forEach(k -> props.put(k, tmp.getProperty(k)));
            }
        }

        return props;
    }

    /**
     * Builder of a deployment configuration for a specific {@link Environment}.
     *
     * <p>
     * Results in {@link Attributes} for {@link Environment} containing the
     * deployment configuration (as {@link Deployable} keys) that is applied to all deployable
     * apps belonging to that {@link Environment}.
     * </p>
     */
    public static class EnvironmentConfig
    {
        // Using setters in this class to allow jetty-xml <Set name="" property="">
        // syntax to skip setting of an environment attribute if property is unset,
        // allowing the in code values to be same defaults as they are in embedded-jetty.

        private final Environment _environment;

        private EnvironmentConfig(Environment environment)
        {
            this._environment = environment;
        }

        /**
         * Load a java properties file as a set of Attributes for this Environment.
         *
         * @param path the path of the properties file
         * @throws IOException if unable to read the properties file
         */
        public void loadProperties(Path path) throws IOException
        {
            Properties props = new Properties();
            try (InputStream inputStream = Files.newInputStream(path))
            {
                props.load(inputStream);
                props.forEach((key, value) -> _environment.setAttribute((String)key, value));
            }
        }

        /**
         * Convenience method for {@code loadProperties(Path.of(pathName));}
         *
         * @param pathName the name of the path to load.
         * @throws IOException if unable to read the properties file
         * @see #loadProperties(Path)
         */
        public void loadPropertiesFromPathName(String pathName) throws IOException
        {
            loadProperties(Path.of(pathName));
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} attribute.
         *
         * @param configurations The configuration class names as a comma separated list
         * @see Deployable#CONFIGURATION_CLASSES
         */
        public void setConfigurationClasses(String configurations)
        {
            setConfigurationClasses(StringUtil.isBlank(configurations) ? null : configurations.split(","));
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONFIGURATION_CLASSES} property.
         *
         * @param configurations The configuration class names.
         * @see Deployable#CONFIGURATION_CLASSES
         */
        public void setConfigurationClasses(String[] configurations)
        {
            if (configurations == null)
                _environment.removeAttribute(Deployable.CONFIGURATION_CLASSES);
            else
                _environment.setAttribute(Deployable.CONFIGURATION_CLASSES, configurations);
        }

        /**
         * This is equivalent to setting the {@link Deployable#CONTAINER_SCAN_JARS} property.
         *
         * @param pattern The regex pattern to use when bytecode scanning container jars
         * @see Deployable#CONTAINER_SCAN_JARS
         */
        public void setContainerScanJarPattern(String pattern)
        {
            _environment.setAttribute(Deployable.CONTAINER_SCAN_JARS, pattern);
        }

        /**
         * The name of the class that this environment uses to create {@link ContextHandler}
         * instances (can be class that implements {@code java.util.function.Supplier<Handler>}
         * as well).
         *
         * <p>
         * This is the fallback class used, if the context class itself isn't defined by
         * the web application being deployed.
         * </p>
         *
         * @param classname the classname for this environment's context deployable.
         * @see Deployable#CONTEXT_HANDLER_CLASS_DEFAULT
         */
        public void setContextHandlerClass(String classname)
        {
            _environment.setAttribute(Deployable.CONTEXT_HANDLER_CLASS_DEFAULT, classname);
        }

        /**
         * Set the defaultsDescriptor.
         * This is equivalent to setting the {@link Deployable#DEFAULTS_DESCRIPTOR} attribute.
         *
         * @param defaultsDescriptor the defaultsDescriptor to set
         * @see Deployable#DEFAULTS_DESCRIPTOR
         */
        public void setDefaultsDescriptor(String defaultsDescriptor)
        {
            _environment.setAttribute(Deployable.DEFAULTS_DESCRIPTOR, defaultsDescriptor);
        }

        /**
         * This is equivalent to setting the {@link Deployable#EXTRACT_WARS} attribute.
         *
         * @param extractWars the extractWars to set
         * @see Deployable#EXTRACT_WARS
         */
        public void setExtractWars(boolean extractWars)
        {
            _environment.setAttribute(Deployable.EXTRACT_WARS, extractWars);
        }

        /**
         * This is equivalent to setting the {@link Deployable#PARENT_LOADER_PRIORITY} attribute.
         *
         * @param parentLoaderPriority the parentLoaderPriority to set
         * @see Deployable#PARENT_LOADER_PRIORITY
         */
        public void setParentLoaderPriority(boolean parentLoaderPriority)
        {
            _environment.setAttribute(Deployable.PARENT_LOADER_PRIORITY, parentLoaderPriority);
        }

        /**
         * This is equivalent to setting the {@link Deployable#SCI_EXCLUSION_PATTERN} property.
         *
         * @param pattern The regex pattern to exclude ServletContainerInitializers from executing
         * @see Deployable#SCI_EXCLUSION_PATTERN
         */
        public void setServletContainerInitializerExclusionPattern(String pattern)
        {
            _environment.setAttribute(Deployable.SCI_EXCLUSION_PATTERN, pattern);
        }

        /**
         * This is equivalent to setting the {@link Deployable#SCI_ORDER} property.
         *
         * @param order The ordered list of ServletContainerInitializer classes to run
         * @see Deployable#SCI_ORDER
         */
        public void setServletContainerInitializerOrder(String order)
        {
            _environment.setAttribute(Deployable.SCI_ORDER, order);
        }

        /**
         * This is equivalent to setting the {@link Deployable#WEBINF_SCAN_JARS} property.
         *
         * @param pattern The regex pattern to use when bytecode scanning web-inf jars
         * @see Deployable#WEBINF_SCAN_JARS
         */
        public void setWebInfScanJarPattern(String pattern)
        {
            _environment.setAttribute(Deployable.WEBINF_SCAN_JARS, pattern);
        }
    }

    public static class MonitoredPathFilter implements FilenameFilter
    {
        @Override
        public boolean accept(File dir, String name)
        {
            if (dir == null || !dir.canRead())
                return false;

            Path path = dir.toPath().resolve(name);

            // We don't monitor subdirectories.
            if (Files.isDirectory(path))
                return false;

            // Synthetic files (like consoles, printers, serial ports, etc) are ignored.
            if (!Files.isRegularFile(path))
                return false;

            try
            {
                // ignore traditional "hidden" path entries.
                if (name.startsWith("."))
                    return false;
                // ignore path tagged as hidden by FS
                if (Files.isHidden(path))
                    return false;
            }
            catch (IOException ignore)
            {
                // ignore
            }

            // The filetypes that are monitored, and we want updates for when they change.
            return FileID.isExtension(name, "jar", "war", "xml", "properties");
        }
    }
}

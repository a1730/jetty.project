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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.PathCollators;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Jetty Environment WebApp Hot Deployment Provider.</p>
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
 * <li>The properties set on the application via embedded calls modifying {@link App#getProperties()}</li>
 * <li>The app specific properties file {@code webapps/<webapp-name>.properties}</li>
 * <li>The environment specific properties file {@code webapps/<environment-name>[-zzz].properties}</li>
 * <li>The {@link Attributes} from the {@link Environment}</li>
 * </ul>
 */
@ManagedObject("Provider for start-up deployment of webapps based on presence in directory")
public class ContextProvider extends ScanningAppProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(ContextProvider.class);
    private String _defaultEnvironmentName;

    public ContextProvider()
    {
        super();
        setFilenameFilter(new Filter());
        setScanInterval(0);
    }

    private static Map<String, String> asProperties(Attributes attributes)
    {
        Map<String, String> props = new HashMap<>();
        attributes.getAttributeNameSet().forEach((name) ->
            {
                Object value = attributes.getAttribute(name);
                String key = name.startsWith(Deployable.ATTRIBUTE_PREFIX)
                    ? name.substring(Deployable.ATTRIBUTE_PREFIX.length())
                    : name;
                props.put(key, Objects.toString(value));
            });
        return props;
    }

    @Override
    public ContextHandler createContextHandler(final App app) throws Exception
    {
        String envName = app.getEnvironmentName();
        Environment environment = Environment.get(StringUtil.isNotBlank(envName) ? envName : getDefaultEnvironmentName());

        if (environment == null)
        {
            LOG.warn("Environment [{}] is not available for app [{}].  The available environments are: {}",
                app.getEnvironmentName(),
                app,
                Environment.getAll().stream()
                    .map(Environment::getName)
                    .collect(Collectors.joining(", ", "[", "]"))
            );
            return null;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("createContextHandler {} in {}", app, environment.getName());

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(environment.getClassLoader());

            app.setEnvironmentName(environment.getName());

            // Create de-aliased file
            Path path = app.getPath().toRealPath();
            if (!Files.exists(path))
                throw new IllegalStateException("App resource does not exist " + path);

            // prepare app attributes to use for app deployment
            Attributes appAttributes = initAttributes(environment, app);

            /*
             * The process now is to figure out the context object to use.
             * This can come from a number of places.
             * 1. If an XML deployable, this is the <Configure class="contextclass"> entry.
             * 2. If another deployable (like a web archive, or directory), then check attributes.
             *    a. use the app attributes to figure out the context handler class.
             *    b. use the environment attributes default context handler class.
             */
            Object context = newContextInstance(environment, app, appAttributes, path);
            if (context == null)
            {
                throw new IllegalStateException("unable to create ContextHandler for " + app);
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Context {} created from app {}", context.getClass().getName(), app);

            // Apply environment properties and XML to context
            if (applyEnvironmentXml(context, environment, appAttributes))
            {
                // If an XML deployable, apply full XML over environment XML changes
                if (FileID.isXml(path))
                    context = applyXml(context, path, environment, appAttributes);
            }

            // Set a backup value for the path to the war in case it hasn't already been set
            // via a different means.  This is especially important for a deployable App
            // that is only a <name>.war file (no XML).  The eventual WebInfConfiguration
            // will use this attribute.
            appAttributes.setAttribute(Deployable.WAR, path.toString());

            // Initialize any deployable
            if (context instanceof Deployable deployable)
                deployable.initializeDefaults(appAttributes);

            return getContextHandler(context);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    /**
     * Initialize a new Context object instance.
     *
     * <p>
     * The search order is:
     * </p>
     * <ol>
     * <li>If app attribute {@link Deployable#CONTEXT_HANDLER_CLASS} is specified, use it, and initialize context</li>
     * <li>If App deployable path is XML, apply XML {@code <Configuration>}</li>
     * <li>Fallback to environment attribute {@link Deployable#CONTEXT_HANDLER_CLASS_DEFAULT}, and initialize context.</li>
     * </ol>
     *
     * @param environment the environment context applies to
     * @param app the App for the context
     * @param appAttributes the Attributes for the App
     * @param path the path of the deployable
     * @return the Context Object.
     * @throws Exception if unable to create Object instance.
     */
    private Object newContextInstance(Environment environment, App app, Attributes appAttributes, Path path) throws Exception
    {
        Object context = newInstance((String)appAttributes.getAttribute(Deployable.CONTEXT_HANDLER_CLASS));
        if (context != null)
        {
            ContextHandler contextHandler = getContextHandler(context);
            if (contextHandler == null)
                throw new IllegalStateException("Unknown context type of " + context);

            initializeContextPath(contextHandler, path);
            initializeContextHandler(contextHandler, path, appAttributes);
            return context;
        }

        if (FileID.isXml(path))
        {
            context = applyXml(null, path, environment, appAttributes);
            ContextHandler contextHandler = getContextHandler(context);
            if (contextHandler == null)
                throw new IllegalStateException("Unknown context type of " + context);
            return context;
        }

        // fallback to default from environment.
        context = newInstance((String)environment.getAttribute(Deployable.CONTEXT_HANDLER_CLASS_DEFAULT));

        if (context != null)
        {
            ContextHandler contextHandler = getContextHandler(context);
            if (contextHandler == null)
                throw new IllegalStateException("Unknown context type of " + context);

            initializeContextPath(contextHandler, path);
            initializeContextHandler(contextHandler, path, appAttributes);
            return context;
        }

        return null;
    }

    private Object newInstance(String className) throws Exception
    {
        if (StringUtil.isBlank(className))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("Attempting to load class {}", className);
        Class<?> clazz = Loader.loadClass(className);
        if (clazz == null)
            return null;
        return clazz.getConstructor().newInstance();
    }

    /**
     * Apply optional environment specific XML to context.
     *
     * @param context the context to apply environment specific behavior to
     * @param environment the environment to use
     * @param appAttributes the attributes of the app
     * @return true it environment specific XML was applied.
     * @throws Exception if unable to apply environment configuration.
     */
    private boolean applyEnvironmentXml(Object context, Environment environment, Attributes appAttributes) throws Exception
    {
        // Collect the optional environment context xml files.
        // Order them according to the name of their property key names.
        List<Path> sortedEnvXmlPaths = appAttributes.getAttributeNameSet()
            .stream()
            .filter(k -> k.startsWith(Deployable.ENVIRONMENT_XML))
            .map(k ->
            {
                Path envXmlPath = Paths.get((String)appAttributes.getAttribute(k));
                if (!envXmlPath.isAbsolute())
                {
                    Path monitoredPath = getMonitoredDirResource().getPath();
                    // not all Resource implementations support java.nio.file.Path.
                    if (monitoredPath != null)
                    {
                        envXmlPath = monitoredPath.getParent().resolve(envXmlPath);
                    }
                }
                return envXmlPath;
            })
            .filter(Files::isRegularFile)
            .sorted(PathCollators.byName(true))
            .toList();

        boolean xmlApplied = false;

        // apply each environment context xml file
        for (Path envXmlPath : sortedEnvXmlPaths)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Applying environment specific context file {}", envXmlPath);
            context = applyXml(context, envXmlPath, environment, appAttributes);
            xmlApplied = true;
        }

        return xmlApplied;
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
        if (_defaultEnvironmentName == null)
        {
            return Environment.getAll().stream()
                .map(Environment::getName)
                .max(Deployable.ENVIRONMENT_COMPARATOR)
                .orElse(null);
        }
        return _defaultEnvironmentName;
    }

    public void setDefaultEnvironmentName(String name)
    {
        this._defaultEnvironmentName = name;
    }

    @Deprecated
    public Map<String, String> getProperties(Environment environment)
    {
        return asProperties(environment);
    }

    public void loadProperties(Environment environment, Path path) throws IOException
    {
        try (InputStream inputStream = Files.newInputStream(path))
        {
            loadProperties(environment, inputStream);
        }
    }

    public void loadProperties(Environment environment, Resource resource) throws IOException
    {
        try (InputStream inputStream = IOResources.asInputStream(resource))
        {
            loadProperties(environment, inputStream);
        }
    }

    public void loadPropertiesFromString(Environment environment, String path) throws IOException
    {
        loadProperties(environment, Path.of(path));
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

    /**
     * To enable support for an {@link Environment}, just ensure it exists.
     *
     * <p>
     * Eg: {@code Environment.ensure("ee11");}
     * </p>
     *
     * <p>
     * To configure Environment specific deployment {@link Attributes},
     * either set the appropriate {@link Deployable} attribute via {@link Attributes#setAttribute(String, Object)},
     * or use the convenience class {@link EnvironmentConfig}.
     * </p>
     *
     * <pre>{@code
     * ContextProvider provider = new ContextProvider();
     * ContextProvider.EnvironmentConfig env10config = provider.configureEnvironment("ee10");
     * env10config.setExtractWars(true);
     * env10config.setParentLoaderPriority(false);
     * }</pre>
     *
     * @see #configureEnvironment(String) instead
     * @deprecated not used anymore.
     */
    @Deprecated(since = "12.1.0", forRemoval = true)
    public void setEnvironmentName(String name)
    {
        Environment.ensure(name);
    }

    protected Object applyXml(Object context, Path xml, Environment environment, Attributes attributes) throws Exception
    {
        if (!FileID.isXml(xml))
            return null;

        XmlConfiguration xmlc = new XmlConfiguration(ResourceFactory.of(this).newResource(xml), null, asProperties(attributes))
        {
            @Override
            public void initializeDefaults(Object context)
            {
                super.initializeDefaults(context);
                ContextHandler contextHandler = getContextHandler(context);
                if (contextHandler == null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Not a ContextHandler: Not initializing Context {}", context);
                }
                else
                {
                    ContextProvider.this.initializeContextPath(contextHandler, xml);
                    ContextProvider.this.initializeContextHandler(contextHandler, xml, attributes);
                }
            }
        };

        xmlc.getIdMap().put("Environment", environment.getName());
        xmlc.setJettyStandardIdsAndProperties(getDeploymentManager().getServer(), xml);

        // Put all Environment attributes into XmlConfiguration as properties that can be used.
        attributes.getAttributeNameSet()
            .stream()
            .filter(k -> !k.startsWith("jetty.home") &&
                !k.startsWith("jetty.base") &&
                !k.startsWith("jetty.webapps"))
            .forEach(k ->
            {
                String v = Objects.toString(attributes.getAttribute(k));
                xmlc.getProperties().put(k, v);
            });

        // Run configure against appropriate classloader.
        ClassLoader xmlClassLoader = getXmlClassLoader(environment, xml);
        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(xmlClassLoader);

        try
        {
            // Create or configure the context
            if (context == null)
                return xmlc.configure();

            return xmlc.configure(context);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
        }
    }

    /**
     * Return a ClassLoader that can load a {@link Environment#CORE} based webapp
     * that is entirely defined within the {@code webapps/} directory.
     *
     * <p>The resulting ClassLoader consists of the following entries:</p>
     * <ol>
     * <li>The java archive {@code <basename>.jar}</li>
     * <li>The java archives {@code <basename>.d/lib/*.jar}</li>
     * <li>The directory {@code <basename>.d/classes/}</li>
     * </ol>
     *
     * @param path to XML defining this webapp, must be absolute, and cannot be in root directory of drive.
     * filename of XML will be used to determine the {@code <basename>} of the other entries in this
     * ClassLoader.
     * @return the classloader for this CORE environment webapp.
     * @throws IOException if unable to apply to create classloader.
     */
    protected ClassLoader findCoreContextClassLoader(Path path) throws IOException
    {
        Path webapps = path.getParent();
        String basename = FileID.getBasename(path).toLowerCase(); // use system Locale as we are dealing with system FS.
        List<URL> urls = new ArrayList<>();

        try (Stream<Path> listingStream = Files.list(webapps))
        {
            // Collect all paths that match "webapps/<basename>*"
            List<Path> basenamePaths = listingStream
                .filter((p) ->
                {
                    String filename = p.getFileName().toString().toLowerCase(); // use system Locale
                    return filename.startsWith(basename);
                })
                .toList();

            // Walk basename paths
            for (Path p : basenamePaths)
            {
                if (Files.isDirectory(p))
                {
                    if (p.getFileName().toString().toLowerCase().endsWith(".d"))
                    {
                        Path libDir = p.resolve("lib");
                        // we have a possible lib directory.
                        if (Files.isDirectory(libDir))
                        {
                            try (Stream<Path> libPaths = Files.list(libDir))
                            {
                                List<Path> javaArchives = libPaths.filter(FileID::isJavaArchive).toList();

                                for (Path lib : javaArchives)
                                {
                                    urls.add(lib.toUri().toURL());
                                }
                            }
                        }

                        Path classesDir = p.resolve(basename + "classes");
                        if (Files.isDirectory(libDir))
                            urls.add(classesDir.toUri().toURL());
                    }
                }

                if (FileID.isJavaArchive(p))
                {
                    urls.add(p.toUri().toURL());
                }
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Core classloader for {}", urls);

        if (urls.isEmpty())
            return null;
        return new URLClassLoader(urls.toArray(new URL[0]), Environment.CORE.getClassLoader());
    }

    /**
     * Find the {@link ContextHandler} for the provided {@link Object}
     *
     * @param context the raw context object
     * @return the {@link ContextHandler} for the context, or null if no ContextHandler associated with context.
     */
    private ContextHandler getContextHandler(Object context)
    {
        if (context == null)
            return null;

        if (context instanceof ContextHandler handler)
            return handler;

        if (Supplier.class.isAssignableFrom(context.getClass()))
        {
            @SuppressWarnings("unchecked")
            Supplier<ContextHandler> provider = (Supplier<ContextHandler>)context;
            return provider.get();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Not a context {}", context);
        return null;
    }

    protected void initializeContextHandler(ContextHandler contextHandler, Path path, Attributes attributes)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("initializeContextHandler {}", contextHandler);

        assert contextHandler != null;

        if (contextHandler.getBaseResource() == null && Files.isDirectory(path))
        {
            ResourceFactory resourceFactory = ResourceFactory.of(contextHandler);
            contextHandler.setBaseResource(resourceFactory.newResource(path));
        }

        // pass through properties as attributes directly
        attributes.getAttributeNameSet().stream()
            .filter((name) -> name.startsWith(Deployable.ATTRIBUTE_PREFIX))
            .forEach((name) ->
            {
                Object value = attributes.getAttribute(name);
                String key = name.substring(Deployable.ATTRIBUTE_PREFIX.length());
                if (LOG.isDebugEnabled())
                    LOG.debug("Setting attribute [{}] to [{}] in context {}", key, value, contextHandler);
                contextHandler.setAttribute(key, value);
            });

        String contextPath = (String)attributes.getAttribute(Deployable.CONTEXT_PATH);
        if (StringUtil.isNotBlank(contextPath))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Context {} initialized with contextPath: {}", contextHandler, contextPath);
            contextHandler.setContextPath(contextPath);
        }
    }

    protected void initializeContextPath(ContextHandler contextHandler, Path path)
    {
        if (contextHandler == null)
            return;

        // Strip any 3 char extension from non directories
        String basename = FileID.getBasename(path);
        String contextPath = basename;

        // special case of archive (or dir) named "root" is / context
        if (contextPath.equalsIgnoreCase("root"))
        {
            contextPath = "/";
        }
        // handle root with virtual host form
        else if (StringUtil.asciiStartsWithIgnoreCase(contextPath, "root-"))
        {
            int dash = contextPath.indexOf('-');
            String virtual = contextPath.substring(dash + 1);
            contextHandler.setVirtualHosts(Arrays.asList(virtual.split(",")));
            contextPath = "/";
        }

        // Ensure "/" is Prepended to all context paths.
        if (contextPath.charAt(0) != '/')
            contextPath = "/" + contextPath;

        if (LOG.isDebugEnabled())
            LOG.debug("ContextHandler {} initialized with displayName: {}", contextHandler, basename);
        contextHandler.setDisplayName(basename);
        if (LOG.isDebugEnabled())
            LOG.debug("ContextHandler {} initialized with contextPath: {}", contextHandler, contextPath);
        contextHandler.setContextPath(contextPath);
    }

    /**
     * Apply the main deployable heuristics referenced in the main javadoc
     * for this class.
     *
     * @param unit the unit of deployment to interrogate.
     * @return the main deployable path
     */
    @Override
    protected Path getMainDeploymentPath(Unit unit)
    {
        List<Path> livePaths = unit.getLivePaths();

        // XML always win.
        List<Path> xmls = livePaths.stream()
            .filter(FileID::isXml)
            .toList();
        if (xmls.size() == 1)
            return xmls.get(0);
        else if (xmls.size() > 1)
            throw new IllegalStateException("More than 1 XML for deployable " + asStringList(xmls));
        // WAR files are next.
        List<Path> wars = livePaths.stream()
            .filter(FileID::isWebArchive)
            .toList();
        if (wars.size() == 1)
            return wars.get(0);
        else if (wars.size() > 1)
            throw new IllegalStateException("More than 1 WAR for deployable " + asStringList(wars));
        // Directories next.
        List<Path> dirs = livePaths.stream()
            .filter(Files::isDirectory)
            .toList();
        if (dirs.size() == 1)
            return dirs.get(0);
        if (dirs.size() > 1)
            throw new IllegalStateException("More than 1 Directory for deployable " + asStringList(dirs));

        throw new IllegalStateException("Unable to determine main deployable for " + unit);
    }

    @Override
    public void unitsChanged(List<Unit> units)
    {
        Set<String> envsChanges = units.stream()
            .map(Unit::getEnvironmentConfigName)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        if (envsChanges.isEmpty())
        {
            // normal set of changes, no environment configuration changes
            // present in the incoming list of changed units.
            super.unitsChanged(units);
        }
        else
        {
            // ensure that the units reported to super.unitsChanged
            // includes all (known) units that belong to the list of changed environments,
            // while filtering out the environment config specific units here.
            List<Unit> unitsWithEnvChanges = new ArrayList<>(units.stream()
                .filter(Unit::isDeployable)
                .toList());

            // Add any missing units into change set.
            for (String envChange : envsChanges)
            {
                for (Unit unit : getUnitsForEnvironment(envChange))
                {
                    if (!unitsWithEnvChanges.contains(unit))
                    {
                        // unit is not part of changeSet but is
                        // in need of a redeploy due to an environment configuration
                        // file being changed.
                        unit.setState(Unit.State.CHANGED);
                        unitsWithEnvChanges.add(unit);
                    }
                }
            }

            super.unitsChanged(unitsWithEnvChanges);
        }
    }

    /**
     * Get the ClassLoader appropriate for applying Jetty XML.
     *
     * @param environment the environment to use
     * @param xml the path to the XML
     * @return the appropriate ClassLoader.
     * @throws IOException if unable to create the ClassLoader
     */
    private ClassLoader getXmlClassLoader(Environment environment, Path xml) throws IOException
    {
        if (Environment.CORE.equals(environment))
        {
            // this XML belongs to a CORE deployment.
            return findCoreContextClassLoader(xml);
        }
        else
        {
            return environment.getClassLoader();
        }
    }

    private Attributes initAttributes(Environment environment, App app) throws IOException
    {
        Attributes attributes = new Attributes.Mapped();

        // Start appAttributes with Attributes from Environment
        environment.getAttributeNameSet().forEach((key) ->
            attributes.setAttribute(key, environment.getAttribute(key)));

        String env = app.getEnvironmentName();

        if (StringUtil.isNotBlank(env))
        {
            // Load environment specific properties files
            Path parent = app.getPath().getParent();
            Properties envProps = loadEnvironmentProperties(parent, env);

            envProps.stringPropertyNames().forEach(
                k -> attributes.setAttribute(k, envProps.getProperty(k))
            );
        }

        // Overlay the app properties
        app.getProperties().forEach(attributes::setAttribute);

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
     * @param directory the directory to load environment properties from.
     * @param env the environment name
     */
    private Properties loadEnvironmentProperties(Path directory, String env) throws IOException
    {
        Properties props = new Properties();
        List<Path> envPropertyFiles = new ArrayList<>();

        // Get all environment specific properties files for this environment,
        // order them according to the lexical ordering of the filenames
        try (Stream<Path> paths = Files.list(directory))
        {
            envPropertyFiles = paths.filter(Files::isRegularFile)
                .filter(p -> FileID.isExtension(p, "properties"))
                .filter(p ->
                {
                    String name = p.getFileName().toString();
                    return name.startsWith(env);
                }).sorted().toList();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Environment property files {}", envPropertyFiles);

        // Load each *.properties file
        for (Path file : envPropertyFiles)
        {
            Path resolvedFile = directory.resolve(file);
            if (Files.exists(resolvedFile))
            {
                Properties tmp = new Properties();
                try (InputStream stream = Files.newInputStream(resolvedFile))
                {
                    tmp.load(stream);
                    //put each property into our substitution pool
                    tmp.stringPropertyNames().forEach(k -> props.put(k, tmp.getProperty(k)));
                }
            }
        }

        return props;
    }

    private void loadProperties(Environment environment, InputStream inputStream) throws IOException
    {
        Properties props = new Properties();
        props.load(inputStream);
        props.stringPropertyNames().forEach((name) ->
            environment.setAttribute(name, props.getProperty(name)));
    }

    private static String asStringList(Collection<Path> paths)
    {
        return paths.stream()
            .sorted(PathCollators.byName(true))
            .map(Path::toString)
            .collect(Collectors.joining(", ", "[", "]"));
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
         *     This is the fallback class used, if the context class itself isn't defined by
         *     the web application being deployed.
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

    public static class Filter implements FilenameFilter
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

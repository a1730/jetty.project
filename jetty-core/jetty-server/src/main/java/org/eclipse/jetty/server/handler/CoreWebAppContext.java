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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A classloader isolated Core WebApp.
 *
 * <p>
 * The Base Resource represents the metadata base that defines this {@code CoreWebAppContext}.
 * </p>
 * <p>
 * The metadata base can be a directory on disk, or a non-traditional {@code war} file with the following contents.
 * </p>
 * <ul>
 *     <li>{@code <metadata>/lib/*.jar} - the jar files for the classloader of this webapp</li>
 *     <li>{@code <metadata>/classes/} - the raw class files for this webapp</li>
 *     <li>{@code <metadata>/static/} - the static content to serve for this webapp</li>
 * </ul>
 * <p>
 *     Note: if using the non-traditional {@code war} as your metadata base, the
 *     existence of {@code <metadata>/lib/*.jar} files means the archive will be
 *     unpacked into the temp directory defined by this core webapp.
 * </p>
 */
public class CoreWebAppContext extends ContextHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(CoreWebAppContext.class);
    private static final String ORIGINAL_BASE_RESOURCE = "org.eclipse.jetty.webapp.originalBaseResource";
    private List<Resource> _extraClasspath;
    private boolean _builtClassLoader = false;

    public CoreWebAppContext()
    {
        this("/");
    }

    public CoreWebAppContext(String contextPath)
    {
        super();
        setContextPath(contextPath);
    }

    /**
     * @return List of Resources that each will represent a new classpath entry
     */
    @ManagedAttribute(value = "extra classpath for context classloader", readonly = true)
    public List<Resource> getExtraClasspath()
    {
        return _extraClasspath == null ? Collections.emptyList() : _extraClasspath;
    }

    /**
     * Set the Extra ClassPath via delimited String.
     * <p>
     * This is a convenience method for {@link #setExtraClasspath(List)}
     * </p>
     *
     * @param extraClasspath Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     * @see #setExtraClasspath(List)
     */
    public void setExtraClasspath(String extraClasspath)
    {
        setExtraClasspath(getResourceFactory().split(extraClasspath));
    }

    public void setExtraClasspath(List<Resource> extraClasspath)
    {
        _extraClasspath = extraClasspath;
    }

    public ResourceFactory getResourceFactory()
    {
        return ResourceFactory.of(this);
    }

    protected Resource unpack(Resource dir) throws IOException
    {
        Path tempDir = getTempDirectory().toPath();
        dir.copyTo(tempDir);
        return ResourceFactory.of(this).newResource(tempDir);
    }

    protected ClassLoader newClassLoader(Resource base) throws IOException
    {
        List<URL> urls = new ArrayList<>();

        if (Resources.isDirectory(base))
        {
            Resource libDir = base.resolve("lib");
            if (Resources.isDirectory(libDir))
            {
                for (Resource entry : libDir.list())
                {
                    URI uri = entry.getURI();
                    if (FileID.isJavaArchive(uri))
                        urls.add(uri.toURL());
                }
            }

            Resource classesDir = base.resolve("classes");
            if (Resources.isDirectory(classesDir))
            {
                urls.add(classesDir.getURI().toURL());
            }
        }

        List<Resource> extraEntries = getExtraClasspath();
        if (extraEntries != null && !extraEntries.isEmpty())
        {
            for (Resource entry : extraEntries)
            {
                urls.add(entry.getURI().toURL());
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Core classloader for {}", urls);

        if (urls.isEmpty())
            return Environment.CORE.getClassLoader();

        return new URLClassLoader(urls.toArray(URL[]::new), Environment.CORE.getClassLoader());
    }

    protected void initWebApp() throws IOException
    {
        if (getBaseResource() == null)
        {
            // Nothing to do.
            return;
        }

        // needs to be a directory (either raw, or archive mounted)
        if (!Resources.isDirectory(getBaseResource()))
        {
            LOG.warn("Invalid Metadata Base Resource (not a directory): {}", getBaseResource());
            return;
        }

        Resource dir = getBaseResource();
        // attempt to figure out if the resource is compressed or not
        if (!dir.getURI().getScheme().equals("file"))
        {
            setAttribute(ORIGINAL_BASE_RESOURCE, dir.getURI());
            dir = unpack(dir);
            setBaseResource(dir);
        }

        Resource staticDir = getBaseResource().resolve("static");
        if (Resources.isDirectory(staticDir))
        {
            ResourceHandler resourceHandler = new ResourceHandler();
            resourceHandler.setBaseResource(staticDir);
            setHandler(resourceHandler);
        }
    }

    @Override
    protected void doStart() throws Exception
    {
        initWebApp();

        if (getClassLoader() == null)
        {
            _builtClassLoader = true;
            // Build Classloader (since once wasn't created)
            setClassLoader(newClassLoader(getBaseResource()));
        }

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        if (_builtClassLoader)
        {
            setClassLoader(null);
        }

        super.doStop();
    }
}

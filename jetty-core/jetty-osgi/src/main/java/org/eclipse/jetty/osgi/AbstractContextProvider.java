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

package org.eclipse.jetty.osgi;

import java.util.Objects;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppProvider;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractContextProvider
 *
 * <p>
 * Base class for DeploymentManager Providers that can deploy ContextHandlers into
 * Jetty that have been discovered via OSGI either as bundles or services.
 * </p>
 */
public abstract class AbstractContextProvider extends AbstractLifeCycle implements AppProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractContextProvider.class);

    private DeploymentManager _deploymentManager;
    private Server _server;
    private ContextFactory _contextFactory;
    private String _environment;
    private final Attributes _attributes = new Attributes.Mapped();

    public AbstractContextProvider(String environment, Server server, ContextFactory contextFactory)
    {
        _environment = Objects.requireNonNull(environment);
        _server = Objects.requireNonNull(server);
        _contextFactory = Objects.requireNonNull(contextFactory);
    }

    public Server getServer()
    {
        return _server;
    }

    public Attributes getAttributes()
    {
        return _attributes;
    }
    
    @Override
    public ContextHandler createContextHandler(App app) throws Exception
    {
        if (app == null)
            return null;

        //Create a ContextHandler suitable to deploy in OSGi
        ContextHandler h = _contextFactory.createContextHandler(this, app);

        return h;
    }

    @Override
    public void setDeploymentManager(DeploymentManager deploymentManager)
    {
        _deploymentManager = deploymentManager;
    }

    @Override
    public String getEnvironmentName()
    {
        // TODO: when AppProvider.getEnvironmentName is eventually removed, leave this method here for
        // these OSGI based AppProviders to use.
        return _environment;
    }

    public DeploymentManager getDeploymentManager()
    {
        return _deploymentManager;
    }
    
    /**
     * @param tldBundles Comma separated list of bundles that contain tld jars
     * that should be setup on the context instances created here.
     */
    public void setTldBundles(String tldBundles)
    {
        _attributes.setAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, tldBundles);
    }

    /**
     * @return The list of bundles that contain tld jars that should be setup on
     * the contexts create here.
     */
    public String getTldBundles()
    {
        return (String)_attributes.getAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);
    }
    
    public boolean isDeployable(Bundle bundle)
    {
        if (bundle == null)
            return false;
        
        //check environment matches
        if (getEnvironmentName().equalsIgnoreCase(bundle.getHeaders().get(OSGiWebappConstants.JETTY_ENVIRONMENT)))
            return true;
        
        return false;
    }

    public boolean isDeployable(ServiceReference<?> service)
    {
        if (service == null)
            return false;
        
        //has it been deployed before?
        if (!StringUtil.isBlank((String)service.getProperty(OSGiWebappConstants.WATERMARK)))
            return false;
        
        //destined for our environment?
        if (getEnvironmentName().equalsIgnoreCase((String)service.getProperty(OSGiWebappConstants.JETTY_ENVIRONMENT)))
            return true;

        return false;
    }
}

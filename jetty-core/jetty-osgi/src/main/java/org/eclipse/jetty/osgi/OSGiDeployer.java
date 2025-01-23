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

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.DeploymentManager;
import org.eclipse.jetty.deploy.bindings.StandardDeployer;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.osgi.util.EventSender;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * OSGiDeployer
 *
 * Extension of standard Jetty deployer that emits OSGi EventAdmin
 * events whenever a webapp is deployed into OSGi via Jetty.
 */
public class OSGiDeployer extends StandardDeployer
{
    private final Server _server;

    public OSGiDeployer(Server server)
    {
        _server = server;
    }

    @Override
    public void processBinding(DeploymentManager deploymentManager, Node node, App app) throws Exception
    {
        ContextHandler contextHandler = app.getContextHandler();
        if (contextHandler == null)
            return;

        //TODO  how to NOT send this event if its not a webapp:
        //OSGi Enterprise Spec only wants an event sent if its a webapp bundle (ie not a ContextHandler)
        if (!(app instanceof OSGiApp))
        {
            doProcessBinding(deploymentManager, node, app);
        }
        else
        {
            String contextPath = contextHandler.getContextPath();
            EventSender.getInstance().send(EventSender.DEPLOYING_EVENT, ((OSGiApp)app).getBundle(), contextPath);
            try
            {
                doProcessBinding(deploymentManager, node, app);
                ((OSGiApp)app).registerAsOSGiService();
                EventSender.getInstance().send(EventSender.DEPLOYED_EVENT, ((OSGiApp)app).getBundle(), contextPath);
            }
            catch (Exception e)
            {
                EventSender.getInstance().send(EventSender.FAILED_EVENT, ((OSGiApp)app).getBundle(), contextPath);
                throw e;
            }
        }
    }

    protected void doProcessBinding(DeploymentManager deploymentManager, Node node, App app) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        ClassLoader cl = (ClassLoader)_server.getAttribute(OSGiServerConstants.SERVER_CLASSLOADER);
        Thread.currentThread().setContextClassLoader(cl);
        try
        {
            super.processBinding(deploymentManager, node, app);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }
}

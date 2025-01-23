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
import org.eclipse.jetty.deploy.bindings.StandardUndeployer;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.osgi.util.EventSender;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * OSGiUndeployer
 *
 * Extension of the Jetty Undeployer which emits OSGi EventAdmin events
 * whenever a webapp is undeployed from Jetty.
 */
public class OSGiUndeployer extends StandardUndeployer
{
    private final Server _server;

    public OSGiUndeployer(Server server)
    {
        _server = server;
    }

    @Override
    public void processBinding(DeploymentManager deploymentManager, Node node, App app) throws Exception
    {
        ContextHandler contextHandler = app.getContextHandler();
        if (contextHandler == null)
            return;

        String contextPath = contextHandler.getContextPath();

        EventSender.getInstance().send(EventSender.UNDEPLOYING_EVENT, ((OSGiApp)app).getBundle(), contextPath);
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
        EventSender.getInstance().send(EventSender.UNDEPLOYED_EVENT, ((OSGiApp)app).getBundle(), contextPath);
        ((OSGiApp)app).deregisterAsOSGiService();
    }
}

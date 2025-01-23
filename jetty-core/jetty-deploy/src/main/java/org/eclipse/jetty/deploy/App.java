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

package org.eclipse.jetty.deploy;

import org.eclipse.jetty.server.handler.ContextHandler;

/**
 * An abstract App, the component that moves through the DeploymentManager.
 */
public interface App
{
    /**
     * The application name.
     *
     * @return the application name.
     */
    String getName();

    /**
     * Get the active ContextHandler for this App.
     *
     * @return the ContextHandler for the App.  null means the ContextHandler hasn't been created yet.
     */
    ContextHandler getContextHandler();
}

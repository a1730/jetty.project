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

/**
 * <p>Represents a single step in one update as a result of a scanner event.</p>
 */
public class DeployAction
{
    public enum Type
    {
        REMOVE,
        ADD;
    }

    private final Type type;
    private final DefaultApp app;

    public DeployAction(Type type, DefaultApp app)
    {
        this.type = type;
        this.app = app;
    }

    public String getName()
    {
        return app.getName();
    }

    public DefaultApp getApp()
    {
        return app;
    }

    public Type getType()
    {
        return type;
    }
}

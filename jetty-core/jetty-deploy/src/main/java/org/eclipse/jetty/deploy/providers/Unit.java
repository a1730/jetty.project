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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.PathCollators;

/**
 * A Unit of deployment, a basename and all the associated
 * paths that belong to that basename, along with state.
 */
public class Unit
{
    public enum State
    {
        UNCHANGED,
        ADDED,
        CHANGED,
        REMOVED
    }

    private final String baseName;
    private final Map<Path, State> paths = new HashMap<>();
    /**
     * Indicates a synthetic Unit, used to track Environment specific configurations.
     * Such as {@code <env>.xml}, {@code <env>.properties}, {@code <env>-<desc>.xml}, {@code <env>-<desc>.properties},
     * all under a single unit that will not actually be deployed.
     */
    private String envConfig;
    private App app;
    private State state;

    public Unit(String basename)
    {
        this.baseName = basename;
        this.state = calcState();
    }

    public String getBaseName()
    {
        return baseName;
    }

    public State getState()
    {
        return state;
    }

    /**
     * <p>
     * Calculate the State of the overall Unit based on the States in the Paths.
     * </p>
     * <dl>
     * <dt>UNCHANGED</dt>
     * <dd>All Path states are in UNCHANGED state</dd>
     * <dt>ADDED</dt>
     * <dd>All Path states are in ADDED state</dd>
     * <dt>CHANGED</dt>
     * <dd>At least one Path state is CHANGED, or there is a variety of states</dd>
     * <dt>REMOVED</dt>
     * <dd>All Path states are in REMOVED state, or there are no Paths being tracked</dd>
     * </dl>
     *
     * @return the state of the Unit.
     */
    private State calcState()
    {
        if (paths.isEmpty())
            return State.REMOVED;

        // Calculate state of unit from Path states.
        Unit.State ret = null;
        for (Unit.State pathState : paths.values())
        {
            switch (pathState)
            {
                case UNCHANGED ->
                {
                    if (ret == null)
                        ret = State.UNCHANGED;
                    else if (ret != State.UNCHANGED)
                        ret = State.CHANGED;
                }
                case ADDED ->
                {
                    if (ret == null)
                        ret = State.ADDED;
                    else if (ret == State.UNCHANGED || ret == State.REMOVED)
                        ret = State.ADDED;
                }
                case CHANGED ->
                {
                    ret = State.CHANGED;
                }
                case REMOVED ->
                {
                    if (ret == null)
                        ret = State.REMOVED;
                    else if (ret != State.REMOVED)
                        ret = State.CHANGED;
                }
            }
        }
        return ret != null ? ret : State.UNCHANGED;
    }

    public Map<Path, State> getPaths()
    {
        return paths;
    }

    /**
     * Return list of Paths being tracked that are live (not-removed).
     *
     * @return the list of live paths.
     */
    public List<Path> getLivePaths()
    {
        return paths
            .entrySet()
            .stream()
            .filter((e) -> e.getValue() != Unit.State.REMOVED)
            .map(Map.Entry::getKey)
            .sorted(PathCollators.byName(true))
            .toList();
    }

    public void putPath(Path path, State state)
    {
        this.paths.put(path, state);
        setState(calcState());
    }

    public boolean isDeployable()
    {
        return StringUtil.isBlank(envConfig);
    }

    public String getEnvironmentName()
    {
        if (app == null)
            return null;
        return app.getEnvironmentName();
    }

    public String getEnvironmentConfigName()
    {
        return envConfig;
    }

    public void setEnvironmentConfigName(String environmentConfigName)
    {
        this.envConfig = environmentConfigName;
    }

    public App getApp()
    {
        return app;
    }

    public void setApp(App app)
    {
        this.app = app;
    }

    public App removeApp()
    {
        App oldApp = this.app;
        this.app = null;
        return oldApp;
    }

    public void resetStates()
    {
        // Drop paths that were removed.
        List<Path> removedPaths = paths.entrySet()
            .stream().filter(e -> e.getValue() == State.REMOVED)
            .map(Map.Entry::getKey)
            .toList();
        for (Path removedPath : removedPaths)
        {
            paths.remove(removedPath);
        }
        // Set all remaining path states to UNCHANGED
        paths.replaceAll((p, v) -> State.UNCHANGED);
        state = calcState();
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == null || getClass() != o.getClass())
            return false;
        Unit unit = (Unit)o;
        return Objects.equals(baseName, unit.baseName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(baseName);
    }

    public void setState(State state)
    {
        this.state = state;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append("Unit[").append(baseName);
        str.append("|").append(getState());
        if (envConfig != null)
            str.append(", envConfig=").append(envConfig);
        str.append(", paths=");
        str.append(paths.entrySet().stream()
            .map((e) -> String.format("%s|%s", e.getKey(), e.getValue()))
            .collect(Collectors.joining(", ", "[", "]"))
        );
        str.append(", app=");
        if (app == null)
            str.append("<null>");
        else
            str.append(app);
        str.append("]");
        return str.toString();
    }
}

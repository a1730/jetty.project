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

package org.eclipse.jetty.deploy.providers.internal;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.deploy.providers.Unit;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The role of DeploymentUnits is to keep track of {@link Unit} instances, and
 * process changes coming from {@link java.util.Scanner} into a set of units
 * that will be processed via a listener.
 */
public class DeploymentUnits implements Scanner.ChangeSetListener
{
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentUnits.class);

    /**
     * Basename of unit, to the Unit instance.
     */
    private Map<String, Unit> units = new HashMap<>();

    private Listener listener;

    public Listener getListener()
    {
        return listener;
    }

    public void setListener(Listener listener)
    {
        this.listener = listener;
    }

    public Unit getUnit(String basename)
    {
        return units.get(basename);
    }

    public Collection<Unit> getUnits()
    {
        return units.values();
    }

    @Override
    public void pathsChanged(Map<Path, Scanner.Notification> changeSet)
    {
        Objects.requireNonNull(changeSet);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("processChanges: changeSet: {}",
                changeSet.entrySet()
                    .stream()
                    .map((e) -> String.format("%s|%s", e.getKey(), e.getValue()))
                    .collect(Collectors.joining(", ", "[", "]"))
            );
        }

        Set<String> changedBaseNames = new HashSet<>();
        Set<String> environmentNames = Environment.getAll().stream()
            .map(Environment::getName).collect(Collectors.toUnmodifiableSet());

        for (Map.Entry<Path, Scanner.Notification> entry : changeSet.entrySet())
        {
            Path path = entry.getKey();
            Unit.State state = toState(entry.getValue());

            // Using lower-case as defined by System Locale, as the files themselves from System FS.
            String basename = FileID.getBasename(path).toLowerCase();

            String envname = getEnvironmentName(basename, environmentNames);
            if (StringUtil.isNotBlank(envname))
            {
                // The file starts with a known environment name.
                // We only care if it is a Environment Configuration file, namely an XML or Properties file.
                if (FileID.isExtension(path, "xml", "properties"))
                {
                    // we have an environment configuration specific path entry.
                    changedBaseNames.add(envname);
                    Unit unit = units.computeIfAbsent(envname, Unit::new);
                    unit.setEnvironmentConfigName(envname);
                    unit.putPath(path, state);
                }
                else
                {
                    LOG.warn("Encountered Path that uses reserved Environment name prefix " +
                        "(This is seen as a configuration for the environment) [{}]: {}", envname, path);
                    // This is something like ee10-app.war
                    // For now, we add it to the basename, but in the future, this will be rejected
                    // as it prevents the use of a configuration file like ee10-app.xml next to it (which
                    // will be seen as a XML to configure the ee10 environment, not an XML for deploying that war)
                    changedBaseNames.add(basename);
                    Unit unit = units.computeIfAbsent(basename, Unit::new);
                    unit.putPath(path, state);
                }
            }
            else
            {
                // we have a normal path entry
                changedBaseNames.add(basename);
                Unit unit = units.computeIfAbsent(basename, Unit::new);
                unit.putPath(path, state);
            }
        }

        Listener listener = getListener();
        if (listener != null)
        {
            List<Unit> changedUnits = changedBaseNames.stream()
                .map(name -> units.get(name))
                .collect(Collectors.toList());

            listener.unitsChanged(changedUnits);
        }
    }

    protected String getEnvironmentName(String basename, Set<String> environmentNames)
    {
        // Handle the case where the basename is part of an environment configuration
        for (String envname : environmentNames)
        {
            if (basename.startsWith(envname))
                return envname;
        }

        return null;
    }

    private Unit.State toState(Scanner.Notification notification)
    {
        return switch (notification)
        {
            case ADDED ->
            {
                yield Unit.State.ADDED;
            }
            case CHANGED ->
            {
                yield Unit.State.CHANGED;
            }
            case REMOVED ->
            {
                yield Unit.State.REMOVED;
            }
        };
    }

    public interface Listener extends EventListener
    {
        void unitsChanged(List<Unit> units);
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.deploy.AbstractCleanEnvironmentTest;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@ExtendWith(WorkDirExtension.class)
public class ContextProviderTest extends AbstractCleanEnvironmentTest
{
    public WorkDir workDir;

    /**
     * Ensuring heuristics defined in javadoc for {@link ContextProvider} is followed.
     * <p>
     * Test with only a single XML.
     */
    @Test
    public void testMainDeploymentPathOnlyXml() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        Unit unit = new Unit("bar");
        unit.putPath(xml, Unit.State.UNCHANGED);

        ContextProvider provider = new ContextProvider();
        Path main = provider.getMainDeploymentPath(unit);

        assertThat("main path", main, equalTo(xml));
    }

    /**
     * Ensuring heuristics defined in javadoc for {@link ContextProvider} is followed.
     * <p>
     * Test with an XML and WAR.
     */
    @Test
    public void testMainDeploymentPathXmlAndWar() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        Unit unit = new Unit("bar");
        unit.putPath(xml, Unit.State.UNCHANGED);
        unit.putPath(war, Unit.State.UNCHANGED);

        ContextProvider provider = new ContextProvider();
        Path main = provider.getMainDeploymentPath(unit);

        assertThat("main path", main, equalTo(xml));
    }

    /**
     * Ensuring heuristics defined in javadoc for {@link ContextProvider} is followed.
     * <p>
     * Test with a Directory and WAR.
     */
    @Test
    public void testMainDeploymentPathDirAndWar() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path appDir = dir.resolve("bar");
        Files.createDirectory(appDir);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        Unit unit = new Unit("bar");
        unit.putPath(appDir, Unit.State.UNCHANGED);
        unit.putPath(war, Unit.State.UNCHANGED);

        ContextProvider provider = new ContextProvider();
        Path main = provider.getMainDeploymentPath(unit);

        assertThat("main path", main, equalTo(war));
    }

    /**
     * Ensuring heuristics defined in javadoc for {@link ContextProvider} is followed.
     * <p>
     * Test with a Directory and XML.
     */
    @Test
    public void testMainDeploymentPathDirAndXml() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path appDir = dir.resolve("bar");
        Files.createDirectory(appDir);
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);

        Unit unit = new Unit("bar");
        unit.putPath(appDir, Unit.State.UNCHANGED);
        unit.putPath(xml, Unit.State.UNCHANGED);

        ContextProvider provider = new ContextProvider();
        Path main = provider.getMainDeploymentPath(unit);

        assertThat("main path", main, equalTo(xml));
    }

    /**
     * Ensuring heuristics defined in javadoc for {@link ContextProvider} is followed.
     * <p>
     * Test with a Directory and XML and WAR
     */
    @Test
    public void testMainDeploymentPathDirAndXmlAndWar() throws IOException
    {
        Path dir = workDir.getEmptyPathDir();
        Path appDir = dir.resolve("bar");
        Files.createDirectory(appDir);
        Path xml = dir.resolve("bar.xml");
        Files.writeString(xml, "XML for bar", UTF_8);
        Path war = dir.resolve("bar.war");
        Files.writeString(war, "WAR for bar", UTF_8);

        Unit unit = new Unit("bar");
        unit.putPath(appDir, Unit.State.UNCHANGED);
        unit.putPath(xml, Unit.State.UNCHANGED);
        unit.putPath(war, Unit.State.UNCHANGED);

        ContextProvider provider = new ContextProvider();
        Path main = provider.getMainDeploymentPath(unit);

        assertThat("main path", main, equalTo(xml));
    }
}

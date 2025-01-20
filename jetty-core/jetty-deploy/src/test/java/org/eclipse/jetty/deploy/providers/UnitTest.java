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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class UnitTest
{
    @Test
    public void testUnitStateUnchanged()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.UNCHANGED);
        unit.putPath(Path.of("test-b"), Unit.State.UNCHANGED);

        assertThat(unit.getState(), is(Unit.State.UNCHANGED));
    }

    @Test
    public void testUnitStateInitialEmpty()
    {
        Unit unit = new Unit("test");
        // intentionally empty of Paths

        assertThat(unit.getState(), is(Unit.State.REMOVED));
    }

    @Test
    public void testUnitStatePutThenRemoveAll()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.ADDED);
        assertThat(unit.getState(), is(Unit.State.ADDED));

        // Now it gets flagged as removed. (eg: by a Scanner change)
        unit.putPath(Path.of("test-a"), Unit.State.REMOVED);
        // Then it gets processed, which results in a state reset.
        unit.resetStates();

        // The resulting Unit should have no paths, and be flagged as removed.
        assertThat(unit.getPaths().size(), is(0));
        assertThat(unit.getState(), is(Unit.State.REMOVED));
    }

    @Test
    public void testUnitStateAddedOnly()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.ADDED);
        unit.putPath(Path.of("test-b"), Unit.State.ADDED);

        assertThat(unit.getState(), is(Unit.State.ADDED));
    }

    @Test
    public void testUnitStateAddedRemoved()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.REMOVED); // existing file removed in this scan
        unit.putPath(Path.of("test-b"), Unit.State.ADDED); // new file introduced in this scan event

        assertThat(unit.getState(), is(Unit.State.CHANGED));
    }

    @Test
    public void testUnitStateAddedChanged()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.CHANGED); // existing file changed in this scan
        unit.putPath(Path.of("test-b"), Unit.State.ADDED); // new file introduced in this scan event

        assertThat(unit.getState(), is(Unit.State.CHANGED));
    }

    @Test
    public void testUnitStateUnchangedAdded()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.UNCHANGED); // existed in previous scan
        unit.putPath(Path.of("test-b"), Unit.State.ADDED); // new file introduced in this scan event

        assertThat(unit.getState(), is(Unit.State.CHANGED));
    }

    @Test
    public void testUnitStateUnchangedChanged()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.UNCHANGED); // existed in previous scan
        unit.putPath(Path.of("test-b"), Unit.State.CHANGED); // existing file changed in this scan event

        assertThat(unit.getState(), is(Unit.State.CHANGED));
    }

    @Test
    public void testUnitStateUnchangedRemoved()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.UNCHANGED); // existed in previous scan
        unit.putPath(Path.of("test-b"), Unit.State.REMOVED); // existing file removed in this scan event

        assertThat(unit.getState(), is(Unit.State.CHANGED));
    }

    @Test
    public void testUnitStateUnchangedRemovedAdded()
    {
        Unit unit = new Unit("test");
        unit.putPath(Path.of("test-a"), Unit.State.UNCHANGED); // existed in previous scan
        unit.putPath(Path.of("test-b"), Unit.State.REMOVED); // existing file changed in this scan event
        unit.putPath(Path.of("test-c"), Unit.State.ADDED); // new file introduced in this scan event

        assertThat(unit.getState(), is(Unit.State.CHANGED));
    }
}

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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DeployActionComparatorTest
{
    @Test
    public void testAddOnly()
    {
        DefaultApp appFoo = new DefaultApp("foo");
        appFoo.putPath(Path.of("foo.xml"), DefaultApp.State.ADDED);
        DefaultApp appBar = new DefaultApp("bar");
        appBar.putPath(Path.of("bar.xml"), DefaultApp.State.ADDED);

        List<DeployAction> actions = new ArrayList<>();
        actions.add(new DeployAction(DeployAction.Type.ADD, new DefaultApp("bar")));
        actions.add(new DeployAction(DeployAction.Type.ADD, new DefaultApp("foo")));

        actions.sort(new DeployActionComparator());

        // Verify order
        Iterator<DeployAction> iterator = actions.iterator();
        DeployAction action;

        // expected in ascending basename order
        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.ADD));
        assertThat(action.getApp().getName(), is("bar"));

        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.ADD));
        assertThat(action.getApp().getName(), is("foo"));
    }

    @Test
    public void testRemoveOnly()
    {
        DefaultApp appFoo = new DefaultApp("foo");
        appFoo.putPath(Path.of("foo.xml"), DefaultApp.State.REMOVED);

        DefaultApp appBar = new DefaultApp("bar");
        appBar.putPath(Path.of("bar.xml"), DefaultApp.State.REMOVED);

        List<DeployAction> actions = new ArrayList<>();
        actions.add(new DeployAction(DeployAction.Type.REMOVE, appFoo));
        actions.add(new DeployAction(DeployAction.Type.REMOVE, appBar));

        actions.sort(new DeployActionComparator());

        // Verify order
        Iterator<DeployAction> iterator = actions.iterator();
        DeployAction action;

        // expected in descending basename order
        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.REMOVE));
        assertThat(action.getApp().getName(), is("foo"));

        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.REMOVE));
        assertThat(action.getApp().getName(), is("bar"));
    }

    @Test
    public void testRemoveTwoAndAddTwo()
    {
        DefaultApp appFoo = new DefaultApp("foo");
        appFoo.putPath(Path.of("foo.xml"), DefaultApp.State.REMOVED);

        DefaultApp appBar = new DefaultApp("bar");
        appBar.putPath(Path.of("bar.xml"), DefaultApp.State.REMOVED);

        List<DeployAction> actions = new ArrayList<>();
        actions.add(new DeployAction(DeployAction.Type.REMOVE, appFoo));
        actions.add(new DeployAction(DeployAction.Type.ADD, appFoo));
        actions.add(new DeployAction(DeployAction.Type.ADD, appBar));
        actions.add(new DeployAction(DeployAction.Type.REMOVE, appBar));

        // Perform sort
        actions.sort(new DeployActionComparator());

        // Verify order
        Iterator<DeployAction> iterator = actions.iterator();
        DeployAction action;

        // expecting REMOVE first

        // REMOVE is in descending basename order
        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.REMOVE));
        assertThat(action.getApp().getName(), is("foo"));

        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.REMOVE));
        assertThat(action.getApp().getName(), is("bar"));

        // expecting ADD next

        // ADD is in ascending basename order
        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.ADD));
        assertThat(action.getApp().getName(), is("bar"));

        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.ADD));
        assertThat(action.getApp().getName(), is("foo"));
    }

    @Test
    public void testRemoveFourAndAddTwo()
    {
        DefaultApp appA = new DefaultApp("app-a");
        appA.putPath(Path.of("app-a.xml"), DefaultApp.State.REMOVED);

        DefaultApp appB = new DefaultApp("app-b");
        appB.putPath(Path.of("app-b.xml"), DefaultApp.State.REMOVED);

        DefaultApp appC = new DefaultApp("app-c");
        appC.putPath(Path.of("app-c.xml"), DefaultApp.State.REMOVED);

        DefaultApp appD = new DefaultApp("app-d");
        appD.putPath(Path.of("app-d.xml"), DefaultApp.State.REMOVED);

        List<DeployAction> actions = new ArrayList<>();
        // app A is going through hot-reload
        actions.add(new DeployAction(DeployAction.Type.REMOVE, appA));
        actions.add(new DeployAction(DeployAction.Type.ADD, appA));
        // app B is being removed
        actions.add(new DeployAction(DeployAction.Type.REMOVE, appB));
        // app C is being removed
        actions.add(new DeployAction(DeployAction.Type.REMOVE, appC));
        // app D is going through hot-reload
        actions.add(new DeployAction(DeployAction.Type.ADD, appD));
        actions.add(new DeployAction(DeployAction.Type.REMOVE, appD));

        assertThat(actions.size(), is(6));

        // Perform sort
        actions.sort(new DeployActionComparator());

        // Verify order
        Iterator<DeployAction> iterator = actions.iterator();
        DeployAction action;

        // expecting REMOVE first

        // REMOVE is in descending basename order
        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.REMOVE));
        assertThat(action.getApp().getName(), is("app-d"));

        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.REMOVE));
        assertThat(action.getApp().getName(), is("app-c"));

        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.REMOVE));
        assertThat(action.getApp().getName(), is("app-b"));

        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.REMOVE));
        assertThat(action.getApp().getName(), is("app-a"));

        // expecting ADD next

        // ADD is in ascending basename order
        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.ADD));
        assertThat(action.getApp().getName(), is("app-a"));

        action = iterator.next();
        assertThat(action.getType(), is(DeployAction.Type.ADD));
        assertThat(action.getApp().getName(), is("app-d"));
    }
}

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

import java.util.List;

import org.eclipse.jetty.util.component.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Isolated;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ensure that each test is using a clean (empty) Environment,
 * and in the case of parallel test execution, is not using
 * a shared Environment.
 */
@Isolated
public abstract class AbstractCleanEnvironmentTest
{
    /**
     * Cleanup the {@link Environment} singleton
     */
    @BeforeEach
    @AfterEach
    public void clearEnvironments()
    {
        List<String> envnames = Environment.getAll().stream()
            .map(Environment::getName)
            .toList();
        for (String envname : envnames)
        {
            Environment.remove(envname);
        }
        assertEquals(0, Environment.getAll().size());
    }
}

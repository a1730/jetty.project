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

package org.eclipse.jetty.security.siwe;

import java.util.function.Function;
import javax.security.auth.Subject;

import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.UserIdentity;
import org.eclipse.jetty.security.UserPrincipal;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Session;

public class AnyUserLoginService implements LoginService
{
    private IdentityService identityService = new DefaultIdentityService();

    @Override
    public String getName()
    {
        return "ANY_USER";
    }

    @Override
    public UserIdentity login(String username, Object credentials, Request request, Function<Boolean, Session> getOrCreateSession)
    {
        UserPrincipal principal = new UserPrincipal(username, null);
        Subject subject = new Subject();
        subject.getPrincipals().add(principal);
        subject.setReadOnly();
        return identityService.newUserIdentity(subject, principal, new String[0]);
    }

    @Override
    public boolean validate(UserIdentity user)
    {
        return user != null;
    }

    @Override
    public IdentityService getIdentityService()
    {
        return identityService;
    }

    @Override
    public void setIdentityService(IdentityService service)
    {
        identityService = service;
    }

    @Override
    public void logout(UserIdentity user)
    {
        // Do nothing.
    }
}

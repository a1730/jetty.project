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

package org.eclipse.jetty.ee9.session.nosql.mongodb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.session.AbstractSessionDataStoreFactory;
import org.eclipse.jetty.session.AbstractSessionDataStoreTest;
import org.eclipse.jetty.session.DefaultSessionCacheFactory;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.SessionContext;
import org.eclipse.jetty.session.SessionData;
import org.eclipse.jetty.session.SessionDataStore;
import org.eclipse.jetty.session.SessionDataStoreFactory;
import org.eclipse.jetty.session.test.tools.MongoTestHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MongoSessionDataStoreTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class MongoSessionDataStoreTest extends AbstractSessionDataStoreTest
{

    private static final String DB_NAME = "DB" + MongoSessionDataStoreTest.class.getSimpleName() + System.nanoTime();

    private static final String COLLECTION_NAME = "COLLECTION" + MongoSessionDataStoreTest.class.getSimpleName() + System.nanoTime();

    public MongoSessionDataStoreTest() throws Exception
    {
        super();
    }

    @BeforeEach
    public void beforeEach() throws Exception
    {
        MongoTestHelper.createCollection(DB_NAME, COLLECTION_NAME);
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        MongoTestHelper.dropCollection(DB_NAME, COLLECTION_NAME);
    }

    @AfterAll
    public static void shutdown() throws Exception
    {
        MongoTestHelper.shutdown();
    }

    @Override
    public SessionDataStoreFactory createSessionDataStoreFactory()
    {
        return MongoTestHelper.newSessionDataStoreFactory(DB_NAME, COLLECTION_NAME);
    }

    @Override
    public void persistSession(SessionData data) throws Exception
    {
        MongoTestHelper.createSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
            data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), data.getAllAttributes(),
                DB_NAME, COLLECTION_NAME);
    }

    @Override
    public void persistUnreadableSession(SessionData data) throws Exception
    {
        MongoTestHelper.createUnreadableSession(data.getId(), data.getContextPath(), data.getVhost(), data.getLastNode(), data.getCreated(),
            data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs(), data.getExpiry(), null,
                DB_NAME, COLLECTION_NAME);
    }

    @Override
    public boolean checkSessionExists(SessionData data) throws Exception
    {
        return MongoTestHelper.checkSessionExists(data.getId(), DB_NAME, COLLECTION_NAME);
    }

    @Override
    public boolean checkSessionPersisted(SessionData data) throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(_contextClassLoader);
        try
        {
            return MongoTestHelper.checkSessionPersisted(data, DB_NAME, COLLECTION_NAME);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Test
    public void testBadWorkerName() throws Exception
    {
        Server server = new Server();
        DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
        idMgr.setWorkerName("b-a-d");
        server.addBean(idMgr);

        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/ctx");

        server.setHandler(context);
        context.getSessionHandler().setSessionIdManager(idMgr);

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setSaveOnCreate(true);
        server.addBean(cacheFactory);

        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        server.addBean(factory);

        assertThrows(IllegalStateException.class, () ->
        {
            server.start();
        });
    }

    @Test
    public void testGoodWorkerName() throws Exception
    {
        Server server = new Server();
        DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
        idMgr.setWorkerName("NODE_99");
        server.addBean(idMgr);

        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/ctx");

        server.setHandler(context);
        context.getSessionHandler().setSessionIdManager(idMgr);

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setSaveOnCreate(true);
        server.addBean(cacheFactory);

        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        server.addBean(factory);

        assertDoesNotThrow(() ->
            server.start());
    }

    @Test
    public void testDefaultWorkerName() throws Exception
    {
        Server server = new Server();
        DefaultSessionIdManager idMgr = new DefaultSessionIdManager(server);
        server.addBean(idMgr);

        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/ctx");

        server.setHandler(context);
        context.getSessionHandler().setSessionIdManager(idMgr);

        DefaultSessionCacheFactory cacheFactory = new DefaultSessionCacheFactory();
        cacheFactory.setSaveOnCreate(true);
        server.addBean(cacheFactory);

        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        server.addBean(factory);

        assertDoesNotThrow(() ->
            server.start());
    }

    /**
     * Test that a session stored in the legacy attribute
     * format can be read.
     */
    @Test
    public void testReadLegacySession() throws Exception
    {
        DefaultSessionIdManager idMgr = new DefaultSessionIdManager(new Server());

        //create the SessionDataStore
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/legacy");
        context.getSessionHandler().getSessionManager().setSessionIdManager(idMgr);
        SessionDataStoreFactory factory = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)factory).setGracePeriodSec(GRACE_PERIOD_SEC);
        SessionDataStore store = factory.getSessionDataStore(context.getSessionHandler().getSessionManager());
        SessionContext sessionContext = new SessionContext(context.getSessionHandler().getSessionManager());
        store.initialize(sessionContext);

        //persist an old-style session

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("attribute1", "attribute1value");
        attributes.put("attribute2", new ArrayList<>(Arrays.asList("1", "2", "3")));
        MongoTestHelper.createLegacySession("1234",
            sessionContext.getCanonicalContextPath(), sessionContext.getVhost(),
            "foo",
            1000L, System.currentTimeMillis() - 1000L, System.currentTimeMillis() - 2000L,
            -1, -1,
            attributes, DB_NAME, COLLECTION_NAME);

        store.start();

        //test that we can retrieve it
        SessionData loaded = store.load("1234");
        assertNotNull(loaded);
        assertEquals("1234", loaded.getId());
        assertEquals(1000L, loaded.getCreated());

        assertEquals("attribute1value", loaded.getAttribute("attribute1"));
        assertNotNull(loaded.getAttribute("attribute2"));

        //test that we can write it
        store.store("1234", loaded);

        //and that it has now been written out with the new format
        MongoTestHelper.checkSessionPersisted(loaded, DB_NAME, COLLECTION_NAME);
    }
}

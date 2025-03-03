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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientFailureTest
{
    private Server server;
    private ServerConnector connector;

    private void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testFailureBeforeRequestCommit() throws Exception
    {
        startServer(new EmptyServerHandler());

        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1)))
        {
            client.start();

            Request request = client.newRequest("localhost", connector.getLocalPort())
                .onRequestHeaders(r -> r.getConnection().close())
                .timeout(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, request::send);

            HttpDestination destination = (HttpDestination)client.resolveDestination(request);
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
            assertEquals(0, connectionPool.getConnectionCount());
            assertEquals(0, connectionPool.getActiveConnections().size());
            assertEquals(0, connectionPool.getIdleConnections().size());
        }
    }

    @Test
    public void testFailureAfterRequestCommit() throws Exception
    {
        startServer(new EmptyServerHandler());

        AtomicReference<HttpConnectionOverHTTP> connectionRef = new AtomicReference<>();
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1)
        {
            @Override
            public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
            {
                HttpConnectionOverHTTP connection = (HttpConnectionOverHTTP)super.newConnection(endPoint, context);
                connectionRef.set(connection);
                return connection;
            }
        }))
        {
            client.start();

            CountDownLatch commitLatch = new CountDownLatch(1);
            CountDownLatch completeLatch = new CountDownLatch(1);
            AsyncRequestContent content = new AsyncRequestContent();
            client.newRequest("localhost", connector.getLocalPort())
                .onRequestCommit(request ->
                {
                    connectionRef.get().getEndPoint().close();
                    commitLatch.countDown();
                })
                .body(content)
                .idleTimeout(2, TimeUnit.SECONDS)
                .send(result ->
                {
                    if (result.isFailed())
                        completeLatch.countDown();
                });

            assertTrue(commitLatch.await(5, TimeUnit.SECONDS));

            // The first chunk will be read but its write will fail.
            content.write(ByteBuffer.allocate(1024), Callback.NOOP);

            // The second chunk is failed because the content is failed.
            CountDownLatch contentLatch = new CountDownLatch(1);
            content.write(ByteBuffer.allocate(1024), new Callback()
            {
                @Override
                public void failed(Throwable x)
                {
                    contentLatch.countDown();
                }
            });

            assertTrue(contentLatch.await(5, TimeUnit.SECONDS));
            assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

            DuplexConnectionPool connectionPool = (DuplexConnectionPool)connectionRef.get().getHttpDestination().getConnectionPool();
            assertEquals(0, connectionPool.getConnectionCount());
            assertEquals(0, connectionPool.getActiveConnections().size());
            assertEquals(0, connectionPool.getIdleConnections().size());
        }
    }

    @Test
    public void testPendingRequestContentThenTotalTimeout() throws Exception
    {
        startServer(new EmptyServerHandler());

        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1)))
        {
            client.start();

            long timeout = 1000;
            AsyncRequestContent content = new AsyncRequestContent();
            AtomicInteger completed = new AtomicInteger();
            CountDownLatch resultLatch = new CountDownLatch(1);
            client.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.POST)
                .body(content)
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .send(result ->
                {
                    // This is invoked only when the total timeout elapses.
                    completed.incrementAndGet();
                    assertThat(result.getRequestFailure(), notNullValue());
                    assertThat(result.getResponseFailure(), nullValue());
                    assertThat(result.getResponse().getStatus(), is(HttpStatus.OK_200));
                    resultLatch.countDown();
                });

            assertTrue(resultLatch.await(2 * timeout, TimeUnit.MILLISECONDS));
            // Verify that the CompleteListener is invoked only once.
            await().during(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(completed::get, is(1));
        }
    }
}

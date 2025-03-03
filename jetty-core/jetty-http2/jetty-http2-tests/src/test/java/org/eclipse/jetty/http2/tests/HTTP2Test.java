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

package org.eclipse.jetty.http2.tests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.Graceful;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP2Test extends AbstractTest
{
    @Test
    public void testRequestNoContentResponseNoContent() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertTrue(stream.getId() > 0);

                assertTrue(frame.isEndStream());
                assertEquals(stream.getId(), frame.getStreamId());
                assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());

                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestNoContentResponseEmptyContent() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        stream.data(new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true), NOOP);
                    }
                });
                return null;
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertFalse(frame.isEndStream());
                assertEquals(stream.getId(), frame.getStreamId());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                assertTrue(data.frame().isEndStream());
                data.release();
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestNoContentResponseContent() throws Exception
    {
        byte[] content = "Hello World!".getBytes(StandardCharsets.UTF_8);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Content.Sink.write(response, true, ByteBuffer.wrap(content));
                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(2);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertTrue(stream.getId() > 0);

                assertFalse(frame.isEndStream());
                assertEquals(stream.getId(), frame.getStreamId());
                assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());

                stream.demand();

                latch.countDown();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                DataFrame frame = data.frame();
                assertTrue(frame.isEndStream());
                assertEquals(ByteBuffer.wrap(content), frame.getByteBuffer());
                data.release();
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestContentResponseContent() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        CountDownLatch latch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        session.newStream(frame, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                if (data.frame().isEndStream())
                    latch.countDown();
                else
                    stream.demand();
            }
        })
        .thenCompose(s -> s.data(new DataFrame(s.getId(), ByteBuffer.allocate(512), false)))
        .thenAccept(s -> s.data(new DataFrame(s.getId(), ByteBuffer.allocate(1024), true)));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMultipleRequests() throws Exception
    {
        String downloadBytes = "X-Download";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                int download = (int)request.getHeaders().getLongField(downloadBytes);
                byte[] content = new byte[download];
                new Random().nextBytes(content);
                response.write(true, ByteBuffer.wrap(content), callback);
                return true;
            }
        });

        int requests = 20;
        Session session = newClientSession(new Session.Listener() {});

        Random random = new Random();
        HttpFields fields = HttpFields.build()
            .put(downloadBytes, random.nextInt(128 * 1024))
            .put("User-Agent", "HTTP2Client/" + Jetty.VERSION);
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(requests);
        for (int i = 0; i < requests; ++i)
        {
            session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
            {
                @Override
                public void onDataAvailable(Stream stream)
                {
                    Stream.Data data = stream.readData();
                    data.release();
                    if (data.frame().isEndStream())
                        latch.countDown();
                    else
                        stream.demand();
                }
            });
        }

        assertTrue(latch.await(requests, TimeUnit.SECONDS), server.dump() + System.lineSeparator() + httpClient.dump());
    }

    @Test
    public void testCustomResponseCode() throws Exception
    {
        int status = 475;
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(status);
                callback.succeeded();
                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(status, response.getStatus());
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHostHeader() throws Exception
    {
        String host = "fooBar";
        int port = 1313;
        String authority = host + ":" + port;
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertEquals(host, Request.getServerName(request));
                assertEquals(port, Request.getServerPort(request));
                callback.succeeded();
                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        HostPortHttpField hostHeader = new HostPortHttpField(authority);
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), hostHeader, "/", HttpVersion.HTTP_2, HttpFields.EMPTY, -1);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(200, response.getStatus());
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSendsGoAwayOnStop() throws Exception
    {
        start(new ServerSessionListener() {});

        CountDownLatch closeLatch = new CountDownLatch(1);
        newClientSession(new Session.Listener()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                closeLatch.countDown();
                callback.succeeded();
            }
        });

        sleep(1000);

        server.stop();

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientSendsGoAwayOnStop() throws Exception
    {
        CountDownLatch closeLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                closeLatch.countDown();
                callback.succeeded();
            }
        });

        newClientSession(new Session.Listener() {});

        sleep(1000);

        http2Client.stop();

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxConcurrentStreams() throws Exception
    {
        int maxStreams = 2;
        start(new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>(1);
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, maxStreams);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY, 0);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }
        });

        CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = newClientSession(new Session.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });
        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request request1 = newRequest("GET", HttpFields.EMPTY);
        FuturePromise<Stream> promise1 = new FuturePromise<>();
        CountDownLatch exchangeLatch1 = new CountDownLatch(2);
        session.newStream(new HeadersFrame(request1, null, false), promise1, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    exchangeLatch1.countDown();
            }
        });
        Stream stream1 = promise1.get(5, TimeUnit.SECONDS);

        MetaData.Request request2 = newRequest("GET", HttpFields.EMPTY);
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        CountDownLatch exchangeLatch2 = new CountDownLatch(2);
        session.newStream(new HeadersFrame(request2, null, false), promise2, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    exchangeLatch2.countDown();
            }
        });
        Stream stream2 = promise2.get(5, TimeUnit.SECONDS);

        // The third stream must not be created.
        MetaData.Request request3 = newRequest("GET", HttpFields.EMPTY);
        CountDownLatch maxStreamsLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(request3, null, false), new Promise.Adapter<>()
        {
            @Override
            public void failed(Throwable x)
            {
                if (x instanceof IllegalStateException)
                    maxStreamsLatch.countDown();
            }
        }, null);

        assertTrue(maxStreamsLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, session.getStreams().size());

        // End the second stream.
        stream2.data(new DataFrame(stream2.getId(), BufferUtil.EMPTY_BUFFER, true), new Callback()
        {
            @Override
            public void succeeded()
            {
                exchangeLatch2.countDown();
            }
        });
        assertTrue(exchangeLatch2.await(5, TimeUnit.SECONDS));
        await().atMost(Duration.ofSeconds(5)).until(() -> session.getStreams().size(), is(1));

        // Create a fourth stream.
        MetaData.Request request4 = newRequest("GET", HttpFields.EMPTY);
        CountDownLatch exchangeLatch4 = new CountDownLatch(2);
        session.newStream(new HeadersFrame(request4, null, true), new Promise.Adapter<>()
        {
            @Override
            public void succeeded(Stream result)
            {
                exchangeLatch4.countDown();
            }
        }, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    exchangeLatch4.countDown();
            }
        });
        assertTrue(exchangeLatch4.await(5, TimeUnit.SECONDS));
        // The stream is removed from the session just after returning from onHeaders(), so wait a little bit.
        await().atMost(Duration.ofSeconds(1)).until(() -> session.getStreams().size(), is(1));

        // End the first stream.
        stream1.data(new DataFrame(stream1.getId(), BufferUtil.EMPTY_BUFFER, true), new Callback()
        {
            @Override
            public void succeeded()
            {
                exchangeLatch1.countDown();
            }
        });
        assertTrue(exchangeLatch2.await(5, TimeUnit.SECONDS));
        assertEquals(0, session.getStreams().size());
    }

    @Test
    public void testInvalidAPIUsageOnClient() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                CompletableFuture<Stream> completable = stream.headers(new HeadersFrame(stream.getId(), response, null, false));
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        data.release();
                        if (data.frame().isEndStream())
                        {
                            completable.thenAccept(s ->
                                s.data(new DataFrame(s.getId(), BufferUtil.EMPTY_BUFFER, true)));
                        }
                        else
                        {
                            stream.demand();
                        }
                    }
                };
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        CountDownLatch completeLatch = new CountDownLatch(2);
        Stream stream = session.newStream(frame, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                if (data.frame().isEndStream())
                    completeLatch.countDown();
                else
                    stream.demand();
            }
        }).get(5, TimeUnit.SECONDS);

        long sleep = 1000;
        DataFrame data1 = new DataFrame(stream.getId(), ByteBuffer.allocate(1024), false)
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                sleep(2 * sleep);
                return super.getByteBuffer();
            }
        };
        DataFrame data2 = new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true);

        new Thread(() ->
        {
            // The first data() call is legal, but slow.
            stream.data(data1, new Callback()
            {
                @Override
                public void succeeded()
                {
                    stream.data(data2, NOOP);
                }
            });
        }).start();

        // Wait for the first data() call to happen.
        sleep(sleep);

        // This data call is illegal because it does not
        // wait for the previous callback to complete.
        stream.data(data2, new Callback()
        {
            @Override
            public void failed(Throwable x)
            {
                if (x instanceof WritePendingException)
                {
                    // Expected.
                    completeLatch.countDown();
                }
            }
        });

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInvalidAPIUsageOnServer() throws Exception
    {
        long sleep = 1000;
        CountDownLatch completeLatch = new CountDownLatch(2);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                DataFrame dataFrame = new DataFrame(stream.getId(), BufferUtil.EMPTY_BUFFER, true);
                // The call to headers() is legal, but slow.
                new Thread(() ->
                {
                    stream.headers(new HeadersFrame(stream.getId(), response, null, false)
                    {
                        @Override
                        public MetaData getMetaData()
                        {
                            sleep(2 * sleep);
                            return super.getMetaData();
                        }
                    }, Callback.from(() -> stream.data(dataFrame, Callback.NOOP), Throwable::printStackTrace));
                }).start();

                // Wait for the headers() call to happen.
                sleep(sleep);

                // This data call is illegal because it does not
                // wait for the previous callback to complete.
                stream.data(dataFrame, new Callback()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        if (x instanceof WritePendingException)
                        {
                            // Expected.
                            completeLatch.countDown();
                        }
                    }
                });

                return null;
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                if (data.frame().isEndStream())
                    completeLatch.countDown();
            }
        });

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCleanGoAwayDoesNotTriggerFailureNotification() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                HeadersFrame response = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(response, Callback.NOOP);
                // Close cleanly.
                stream.getSession().close(ErrorCode.NO_ERROR.code, null, Callback.NOOP);
                return null;
            }
        });

        CountDownLatch closeLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        Session session = newClientSession(new Session.Listener()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                closeLatch.countDown();
                callback.succeeded();
            }

            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                failureLatch.countDown();
                callback.succeeded();
            }
        });
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        session.newStream(request, new Promise.Adapter<>(), null);

        // Make sure onClose() is called.
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertFalse(failureLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testClientInvalidHeader() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        // A bad header in the request should fail on the client.
        Session session = newClientSession(new Session.Listener() {});
        HttpFields requestFields = HttpFields.build()
            .put(":custom", "special");
        MetaData.Request metaData = newRequest("GET", requestFields);
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(request, promise, null);
        ExecutionException x = assertThrows(ExecutionException.class, () -> promise.get(5, TimeUnit.SECONDS));
        assertThat(x.getCause(), instanceOf(HpackException.StreamException.class));
    }

    @Test
    public void testServerInvalidHeader() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(":custom", "special");
                callback.succeeded();
                return true;
            }
        });

        // Good request with bad header in the response.
        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch resetLatch = new CountDownLatch(1);
        session.newStream(request, promise, new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                resetLatch.countDown();
                callback.succeeded();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        assertNotNull(stream);

        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerInvalidHeaderFlushed() throws Exception
    {
        CountDownLatch serverFailure = new CountDownLatch(1);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(":custom", "special");
                try
                {
                    Content.Sink.write(response, false, null);
                }
                catch (IOException x)
                {
                    assertThat(x.getCause(), instanceOf(HpackException.StreamException.class));
                    serverFailure.countDown();
                    throw x;
                }
                return true;
            }
        });

        // Good request with bad header in the response.
        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", "/flush", HttpFields.EMPTY);
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch resetLatch = new CountDownLatch(1);
        session.newStream(request, promise, new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                // Cannot receive a 500 because we force the flush on the server, so
                // the response is committed even if the server was not able to write it.
                resetLatch.countDown();
                callback.succeeded();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        assertNotNull(stream);
        assertTrue(serverFailure.await(5, TimeUnit.SECONDS));
        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGracefulServerGoAway() throws Exception
    {
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        CountDownLatch serverSessionLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(2);
        start(new ServerSessionListener()
        {
            @Override
            public void onAccept(Session session)
            {
                serverSessionRef.set(session);
                serverSessionLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        data.release();
                        dataLatch.countDown();
                        if (data.frame().isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                            stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        }
                        else
                        {
                            stream.demand();
                        }
                    }
                };
            }
        });
        // Avoid aggressive idle timeout to allow the test verifications.
        connector.setShutdownIdleTimeout(connector.getIdleTimeout());

        CountDownLatch clientGracefulGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                if (frame.isGraceful())
                    clientGracefulGoAwayLatch.countDown();
                else
                    clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseLatch.countDown();
                callback.succeeded();
            }
        });
        assertTrue(serverSessionLatch.await(5, TimeUnit.SECONDS));
        Session serverSession = serverSessionRef.get();

        // Start 2 requests without completing them yet.
        CountDownLatch responseLatch = new CountDownLatch(2);
        MetaData.Request metaData1 = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame request1 = new HeadersFrame(metaData1, null, false);
        FuturePromise<Stream> promise1 = new FuturePromise<>();
        Stream.Listener listener = new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    responseLatch.countDown();
                }
            }
        };
        clientSession.newStream(request1, promise1, listener);
        Stream stream1 = promise1.get(5, TimeUnit.SECONDS);
        stream1.data(new DataFrame(stream1.getId(), ByteBuffer.allocate(1), false), Callback.NOOP);

        MetaData.Request metaData2 = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame request2 = new HeadersFrame(metaData2, null, false);
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        clientSession.newStream(request2, promise2, listener);
        Stream stream2 = promise2.get(5, TimeUnit.SECONDS);
        stream2.data(new DataFrame(stream2.getId(), ByteBuffer.allocate(1), false), Callback.NOOP);

        assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        // Both requests are now on the server, shutdown gracefully the server session.
        int port = connector.getLocalPort();
        CompletableFuture<Void> shutdown = Graceful.shutdown(server);

        // Client should receive the graceful GOAWAY.
        assertTrue(clientGracefulGoAwayLatch.await(5, TimeUnit.SECONDS));
        // Client should not receive the non-graceful GOAWAY.
        assertFalse(clientGoAwayLatch.await(500, TimeUnit.MILLISECONDS));
        // Client should not be closed yet.
        assertFalse(clientCloseLatch.await(500, TimeUnit.MILLISECONDS));

        // Client cannot create new requests after receiving a GOAWAY.
        HostPortHttpField authority3 = new HostPortHttpField("localhost" + ":" + port);
        MetaData.Request metaData3 = new MetaData.Request("GET", HttpScheme.HTTP.asString(), authority3, "/", HttpVersion.HTTP_2, HttpFields.EMPTY, -1);
        HeadersFrame request3 = new HeadersFrame(metaData3, null, true);
        FuturePromise<Stream> promise3 = new FuturePromise<>();
        clientSession.newStream(request3, promise3, null);
        assertThrows(ExecutionException.class, () -> promise3.get(5, TimeUnit.SECONDS));

        // Finish the previous requests and expect the responses.
        stream1.data(new DataFrame(stream1.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        stream2.data(new DataFrame(stream2.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP);
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        assertNull(shutdown.get(5, TimeUnit.SECONDS));

        // Now GOAWAY should arrive to the client.
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));

        assertFalse(((HTTP2Session)clientSession).getEndPoint().isOpen());
        assertFalse(((HTTP2Session)serverSession).getEndPoint().isOpen());
    }

    @Test
    public void testClientSendsLargeHeader() throws Exception
    {
        CountDownLatch settingsLatch = new CountDownLatch(2);

        CompletableFuture<Throwable> serverFailureFuture = new CompletableFuture<>();
        CompletableFuture<String> serverCloseReasonFuture = new CompletableFuture<>();
        start(new ServerSessionListener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                serverFailureFuture.complete(failure);
                ServerSessionListener.super.onFailure(session, failure, callback);
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                serverCloseReasonFuture.complete(frame.tryConvertPayload());
                ServerSessionListener.super.onClose(session, frame, callback);
            }
        });

        CompletableFuture<Throwable> clientFailureFuture = new CompletableFuture<>();
        CompletableFuture<String> clientCloseReasonFuture = new CompletableFuture<>();
        Session.Listener listener = new Session.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                clientFailureFuture.complete(failure);
                Session.Listener.super.onFailure(session, failure, callback);
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseReasonFuture.complete(frame.tryConvertPayload());
                Session.Listener.super.onClose(session, frame, callback);
            }
        };

        HTTP2Session session = (HTTP2Session)newClientSession(listener);
        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));
        session.getGenerator().getHpackEncoder().setMaxHeaderListSize(1024 * 1024);

        String value = "x".repeat(8 * 1024);
        HttpFields requestFields = HttpFields.build()
            .put("custom", value);
        MetaData.Request metaData = newRequest("GET", requestFields);
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        session.newStream(request, new FuturePromise<>(), new Stream.Listener() {});

        // Test failure and close reason on client.
        String closeReason = clientCloseReasonFuture.get(5, TimeUnit.SECONDS);
        assertThat(closeReason, equalTo("invalid_hpack_block"));
        assertNull(clientFailureFuture.getNow(null));

        // Test failure and close reason on server.
        closeReason = serverCloseReasonFuture.get(5, TimeUnit.SECONDS);
        assertThat(closeReason, equalTo("invalid_hpack_block"));
        Throwable failure = serverFailureFuture.get(5, TimeUnit.SECONDS);
        assertThat(failure, instanceOf(IOException.class));
        assertThat(failure.getMessage(), containsString("invalid_hpack_block"));
    }

    @Test
    public void testServerSendsLargeHeader() throws Exception
    {
        int maxResponseHeadersSize = 8 * 1024;
        CompletableFuture<Throwable> serverFailureFuture = new CompletableFuture<>();
        CompletableFuture<String> serverCloseReasonFuture = new CompletableFuture<>();
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HTTP2Session session = (HTTP2Session)stream.getSession();
                session.getGenerator().getHpackEncoder().setMaxHeaderListSize(2 * maxResponseHeadersSize);

                String value = "x".repeat(maxResponseHeadersSize);
                HttpFields fields = HttpFields.build().put("custom", value);
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, fields);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true));
                return null;
            }

            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                serverFailureFuture.complete(failure);
                ServerSessionListener.super.onFailure(session, failure, callback);
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                serverCloseReasonFuture.complete(frame.tryConvertPayload());
                ServerSessionListener.super.onClose(session, frame, callback);
            }
        });

        http2Client.setMaxResponseHeadersSize(maxResponseHeadersSize);
        CompletableFuture<Throwable> clientFailureFuture = new CompletableFuture<>();
        CompletableFuture<String> clientCloseReasonFuture = new CompletableFuture<>();
        Session.Listener listener = new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                clientFailureFuture.complete(failure);
                Session.Listener.super.onFailure(session, failure, callback);
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseReasonFuture.complete(frame.tryConvertPayload());
                Session.Listener.super.onClose(session, frame, callback);
            }
        };

        Session session = newClientSession(listener);
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame request = new HeadersFrame(metaData, null, true);
        session.newStream(request, new FuturePromise<>(), new Stream.Listener() {});

        // Test failure and close reason on server.
        String closeReason = serverCloseReasonFuture.get(5, TimeUnit.SECONDS);
        assertThat(closeReason, equalTo("invalid_hpack_block"));
        assertNull(serverFailureFuture.getNow(null));

        // Test failure and close reason on client.
        closeReason = clientCloseReasonFuture.get(5, TimeUnit.SECONDS);
        assertThat(closeReason, equalTo("invalid_hpack_block"));
        Throwable failure = clientFailureFuture.get(5, TimeUnit.SECONDS);
        assertThat(failure, instanceOf(IOException.class));
        assertThat(failure.getMessage(), containsString("invalid_hpack_block"));
    }

    @Test
    public void testClientExceedsConnectionMaxUsage() throws Exception
    {
        start(new ServerSessionListener() {});

        Session session = newClientSession(new Session.Listener() {});
        ((HTTP2Session)session).setMaxTotalLocalStreams(1);

        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {});

        // Must not be able to create more streams than allowed.
        assertThrows(ExecutionException.class, () -> session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS));

        // Must not be able to create more streams than allowed with an explicit streamId.
        int explicitStreamId = Integer.MAX_VALUE;
        assertThrows(ExecutionException.class, () -> session.newStream(new HeadersFrame(explicitStreamId, request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS));

        // Session must still be valid.
        assertFalse(session.isClosed());
    }

    @Test
    public void testClientExceedsMaxStreamId() throws Exception
    {
        start(new ServerSessionListener() {});

        Session session = newClientSession(new Session.Listener() {});

        // Use the max possible streamId.
        int explicitStreamId = Integer.MAX_VALUE;
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(explicitStreamId, request, null, true), new Stream.Listener() {});

        // Must not be able to create more streams.
        assertThrows(ExecutionException.class, () -> session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS));

        assertThrows(ExecutionException.class, () -> session.newStream(new HeadersFrame(explicitStreamId, request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS));

        // Session must still be valid.
        assertFalse(session.isClosed());
    }

    @Test
    public void testClientCreatesStreamsWithExplicitStreamId() throws Exception
    {
        start(new ServerSessionListener() {});

        Session session = newClientSession(new Session.Listener() {});

        int evenStreamId = 128;
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        assertThrows(ExecutionException.class, () -> session.newStream(new HeadersFrame(evenStreamId, request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS));

        // Equivalent to Integer.MAX_VALUE + 2.
        int negativeStreamId = Integer.MIN_VALUE + 1;
        assertThrows(ExecutionException.class, () -> session.newStream(new HeadersFrame(negativeStreamId, request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS));

        int explicitStreamId = 127;
        Stream stream = session.newStream(new HeadersFrame(explicitStreamId, request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS);
        assertThat(stream.getId(), equalTo(explicitStreamId));

        stream = session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS);
        assertThat(stream.getId(), equalTo(explicitStreamId + 2));

        // Cannot create streams with smaller id.
        int smallerStreamId = explicitStreamId - 2;
        assertThrows(ExecutionException.class, () -> session.newStream(new HeadersFrame(smallerStreamId, request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS));

        // Should be possible to create the stream with the max id.
        explicitStreamId = Integer.MAX_VALUE;
        session.newStream(new HeadersFrame(explicitStreamId, request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS);

        // After the stream with the max id, cannot create more streams on this connection.
        assertThrows(ExecutionException.class, () -> session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS));
        // Session must still be valid.
        assertFalse(session.isClosed());
    }

    @Test
    public void testServerPushesStreamsWithExplicitStreamId() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                try
                {
                    int oddStreamId = 129;
                    MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
                    assertThrows(ExecutionException.class, () -> stream.push(new PushPromiseFrame(stream.getId(), oddStreamId, request), new Stream.Listener() {})
                        .get(5, TimeUnit.SECONDS));

                    int negativeStreamId = Integer.MIN_VALUE;
                    assertThrows(ExecutionException.class, () -> stream.push(new PushPromiseFrame(stream.getId(), negativeStreamId, request), new Stream.Listener() {})
                        .get(5, TimeUnit.SECONDS));

                    int explicitStreamId = 128;
                    Stream pushedStream = stream.push(new PushPromiseFrame(stream.getId(), explicitStreamId, request), new Stream.Listener() {})
                        .get(5, TimeUnit.SECONDS);
                    assertThat(pushedStream.getId(), equalTo(explicitStreamId));

                    pushedStream = stream.push(new PushPromiseFrame(stream.getId(), 0, request), new Stream.Listener() {})
                        .get(5, TimeUnit.SECONDS);
                    assertThat(pushedStream.getId(), equalTo(explicitStreamId + 2));

                    // Cannot push streams with smaller id.
                    int smallerStreamId = explicitStreamId - 2;
                    assertThrows(ExecutionException.class, () -> stream.push(new PushPromiseFrame(stream.getId(), smallerStreamId, request), new Stream.Listener() {})
                        .get(5, TimeUnit.SECONDS));

                    // Should be possible to push the stream with the max id.
                    explicitStreamId = Integer.MAX_VALUE - 1;
                    stream.push(new PushPromiseFrame(stream.getId(), explicitStreamId, request), new Stream.Listener() {})
                        .get(5, TimeUnit.SECONDS);

                    // After the stream with the max id, cannot push more streams on this connection.
                    assertThrows(ExecutionException.class, () -> stream.push(new PushPromiseFrame(stream.getId(), 0, request), new Stream.Listener() {})
                        .get(5, TimeUnit.SECONDS));
                    // Session must still be valid.
                    assertFalse(stream.getSession().isClosed());

                    latch.countDown();

                    return null;
                }
                catch (Throwable x)
                {
                    throw new RuntimeException(x);
                }
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request, null, true), new Stream.Listener() {})
            .get(5, TimeUnit.SECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLargeRequestHeaders() throws Exception
    {
        int maxHeadersSize = 20 * 1024;
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setRequestHeaderSize(2 * maxHeadersSize);
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        }, httpConfig);
        connector.getBean(AbstractHTTP2ServerConnectionFactory.class).setMaxFrameSize(17 * 1024);
        http2Client.setMaxFrameSize(18 * 1024);
        http2Client.setMaxRequestHeadersSize(2 * maxHeadersSize);

        // Wait for the SETTINGS frame to be exchanged.
        CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = newClientSession(new Session.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });
        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));
        EndPoint serverEndPoint = connector.getConnectedEndPoints().iterator().next();
        HTTP2Connection connection = (HTTP2Connection)serverEndPoint.getConnection();
        await().atMost(5, TimeUnit.SECONDS).until(() -> connection.getSession().getGenerator().getMaxFrameSize() == http2Client.getMaxFrameSize());

        CountDownLatch responseLatch = new CountDownLatch(1);
        HttpFields.Mutable headers = HttpFields.build()
            // Symbol "<" needs 15 bits to be Huffman encoded,
            // while letters/numbers take typically less than
            // 8 bits, and here we want to exceed maxHeadersSize.
            .put("X-Large", "<".repeat(maxHeadersSize));
        MetaData.Request request = newRequest("GET", headers);
        session.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                assertTrue(frame.isEndStream());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                responseLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    private static void sleep(long time)
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException();
        }
    }
}

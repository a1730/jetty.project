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

package org.eclipse.jetty.alpn.conscrypt.server;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Security;

import org.conscrypt.OpenSSLProvider;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test server that verifies that the Conscrypt ALPN mechanism works for both server and client side
 */
@DisabledOnOs(architectures = "aarch64", disabledReason = "Conscrypt does not provide aarch64 native libs as of version 2.5.2")
public class ConscryptHTTP2ServerTest
{
    static
    {
        Security.addProvider(new OpenSSLProvider());
    }

    private final HttpConfiguration httpsConfig = new HttpConfiguration();
    private final Server server = new Server();

    private SslContextFactory.Server newServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        configureSslContextFactory(sslContextFactory);
        return sslContextFactory;
    }

    private SslContextFactory.Client newClientSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        configureSslContextFactory(sslContextFactory);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        Path path = Paths.get("src", "test", "resources");
        File keys = path.resolve("keystore.p12").toFile();
        sslContextFactory.setKeyStorePath(keys.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setProvider("Conscrypt");
        if (JavaVersion.VERSION.getPlatform() < 9)
        {
            // Conscrypt enables TLSv1.3 by default but it's not supported in Java 8.
            sslContextFactory.addExcludeProtocols("TLSv1.3");
        }
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        httpsConfig.setSecureScheme("https");
        httpsConfig.setSendXPoweredBy(true);
        httpsConfig.setSendServerVersion(true);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        HttpConnectionFactory http = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(http.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(newServerSslContextFactory(), alpn.getProtocol());

        ServerConnector http2Connector = new ServerConnector(server, ssl, alpn, h2, http);
        http2Connector.setPort(0);
        server.addConnector(http2Connector);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testSimpleRequest() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        HTTP2Client h2Client = new HTTP2Client(clientConnector);
        try (HttpClient client = new HttpClient(new HttpClientTransportOverHTTP2(h2Client)))
        {
            client.start();
            int port = ((ServerConnector)server.getConnectors()[0]).getLocalPort();
            ContentResponse contentResponse = client.GET("https://localhost:" + port);
            assertEquals(200, contentResponse.getStatus());
        }
    }

    @Test
    public void testSNIRequired() throws Exception
    {
        // The KeyStore contains 1 certificate with two DNS names.
        httpsConfig.getCustomizer(SecureRequestCustomizer.class).setSniRequired(true);
        testSimpleRequest();
    }
}

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

package org.eclipse.jetty.http3.client.transport;

import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientConnectionFactory;
import org.eclipse.jetty.http3.client.transport.internal.SessionClientListener;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.client.QuicTransport;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class ClientConnectionFactoryOverHTTP3 extends ContainerLifeCycle implements ClientConnectionFactory, HttpClient.Aware
{
    private final HTTP3ClientConnectionFactory factory = new HTTP3ClientConnectionFactory();
    private final HTTP3Client http3Client;

    public ClientConnectionFactoryOverHTTP3(HTTP3Client http3Client)
    {
        this.http3Client = http3Client;
        installBean(http3Client);
    }

    @Override
    public void setHttpClient(HttpClient httpClient)
    {
        HttpClientTransportOverHTTP3.configure(httpClient, http3Client);
    }

    @Override
    public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        return factory.newConnection(endPoint, context);
    }

    /**
     * <p>Representation of the {@code HTTP/3} application protocol used by {@link HttpClientTransportDynamic}.</p>
     *
     * @see HttpClientConnectionFactory#HTTP11
     */
    public static class HTTP3 extends Info implements ProtocolSession.Factory
    {
        private static final List<String> protocols = List.of("h3");

        private final HTTP3Client http3Client;

        public HTTP3(HTTP3Client client)
        {
            super(new ClientConnectionFactoryOverHTTP3(client));
            http3Client = client;
        }

        public HTTP3Client getHTTP3Client()
        {
            return http3Client;
        }

        @Override
        public List<String> getProtocols(boolean secure)
        {
            return protocols;
        }

        @Override
        public Transport newTransport()
        {
            return new QuicTransport(getHTTP3Client().getQuicConfiguration());
        }

        @Override
        public ProtocolSession newProtocolSession(QuicSession quicSession, Map<String, Object> context)
        {
            ClientConnectionFactoryOverHTTP3 http3 = (ClientConnectionFactoryOverHTTP3)getClientConnectionFactory();
            context.put(HTTP3Client.CLIENT_CONTEXT_KEY, http3Client);
            SessionClientListener listener = new SessionClientListener(context);
            context.put(HTTP3Client.SESSION_LISTENER_CONTEXT_KEY, listener);
            return http3.factory.newProtocolSession(quicSession, context);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", getClass().getSimpleName(), hashCode());
        }
    }
}

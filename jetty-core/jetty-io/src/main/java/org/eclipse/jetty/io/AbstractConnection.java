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

package org.eclipse.jetty.io;

import java.util.EventListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A convenience base implementation of {@link Connection}.</p>
 * <p>This class uses the capabilities of the {@link EndPoint} API to provide a
 * more traditional style of async reading.  A call to {@link #fillInterested()}
 * will schedule a callback to {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
 * as appropriate.</p>
 */
public abstract class AbstractConnection implements Connection, Invocable
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractConnection.class);

    private final List<Listener> _listeners = new CopyOnWriteArrayList<>();
    private final Callback _fillableCallback = new FillableCallback();
    private final long _created = System.currentTimeMillis();
    private final EndPoint _endPoint;
    private final Executor _executor;
    private int _inputBufferSize = 2048;

    protected AbstractConnection(EndPoint endPoint, Executor executor)
    {
        if (executor == null)
            throw new IllegalArgumentException("Executor must not be null!");
        _endPoint = endPoint;
        _executor = executor;
    }

    @Override
    public void addEventListener(EventListener listener)
    {
        if (listener instanceof Listener)
            _listeners.add((Listener)listener);
    }

    @Override
    public void removeEventListener(EventListener eventListener)
    {
        if (eventListener instanceof Listener listener)
            _listeners.remove(listener);
    }

    public int getInputBufferSize()
    {
        return _inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        _inputBufferSize = inputBufferSize;
    }

    protected Executor getExecutor()
    {
        return _executor;
    }

    /**
     * <p>Registers read interest using the default {@link Callback} with {@link Invocable.InvocationType#BLOCKING}.</p>
     * <p>When read readiness is signaled, {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
     * will be invoked.</p>
     * <p>This method should be used sparingly, mainly from {@link #onOpen()}, and {@link #fillInterested(Callback)}
     * should be preferred instead, passing a {@link Callback} that specifies the {@link Invocable.InvocationType}
     * for each specific case where read interest needs to be registered.</p>
     *
     * @see #fillInterested(Callback)
     * @see #onFillable()
     * @see #onFillInterestedFailed(Throwable)
     */
    public void fillInterested()
    {
        fillInterested(_fillableCallback);
    }

    /**
     * <p>Registers read interest with the given callback.</p>
     * <p>When read readiness is signaled, the callback will be completed.</p>
     *
     * @param callback the callback to complete when read readiness is signaled
     */
    public void fillInterested(Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("fillInterested {} {}", callback, this);
        getEndPoint().fillInterested(callback);
    }

    public void tryFillInterested(Callback callback)
    {
        getEndPoint().tryFillInterested(callback);
    }

    public boolean isFillInterested()
    {
        return getEndPoint().isFillInterested();
    }

    /**
     * <p>Callback method invoked when the endpoint is ready to be read.</p>
     *
     * @see #fillInterested(Callback)
     */
    public abstract void onFillable();

    /**
     * <p>Callback method invoked when the endpoint failed to be ready to be read.</p>
     *
     * @param cause the exception that caused the failure
     */
    public void onFillInterestedFailed(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFillInterestedFailed {}", this, cause);
        if (_endPoint.isOpen())
        {
            boolean close = true;
            if (cause instanceof TimeoutException timeout)
                close = onReadTimeout(timeout);
            if (close)
            {
                if (_endPoint.isOutputShutdown())
                    _endPoint.close();
                else
                {
                    _endPoint.shutdownOutput();
                    fillInterested();
                }
            }
        }
    }

    /**
     * <p>Callback method invoked when the endpoint failed to be ready to be read after a timeout</p>
     *
     * @param timeout the cause of the read timeout
     * @return true to signal that the endpoint must be closed, false to keep the endpoint open
     */
    protected boolean onReadTimeout(TimeoutException timeout)
    {
        return true;
    }

    @Override
    public void onOpen()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", this);

        for (Listener listener : _listeners)
        {
            onOpened(listener);
        }
    }

    private void onOpened(Listener listener)
    {
        try
        {
            listener.onOpened(this);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    @Override
    public void onClose(Throwable cause)
    {
        if (LOG.isDebugEnabled())
        {
            if (cause == null)
                LOG.debug("onClose {}", this);
            else
                LOG.debug("onClose {}", this, cause);
        }
        for (Listener listener : _listeners)
        {
            onClosed(listener);
        }
    }

    private void onClosed(Listener listener)
    {
        try
        {
            listener.onClosed(this);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.info("Failure while notifying listener {}", listener, x);
            else
                LOG.info("Failure while notifying listener {} {}", listener, x.toString());
        }
    }

    @Override
    public EndPoint getEndPoint()
    {
        return _endPoint;
    }

    @Override
    public void close()
    {
        getEndPoint().close();
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
    {
        return true;
    }

    @Override
    public long getMessagesIn()
    {
        return -1;
    }

    @Override
    public long getMessagesOut()
    {
        return -1;
    }

    @Override
    public long getBytesIn()
    {
        return -1;
    }

    @Override
    public long getBytesOut()
    {
        return -1;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return _created;
    }

    @Override
    public final String toString()
    {
        return String.format("%s@%x::%s", getClass().getSimpleName(), hashCode(), getEndPoint());
    }

    public String toConnectionString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }

    public abstract static class NonBlocking extends AbstractConnection
    {
        private final Callback _nonBlockingReadCallback = new NonBlockingFillableCallback();

        public NonBlocking(EndPoint endPoint, Executor executor)
        {
            super(endPoint, executor);
        }

        /**
         * <p>Registers read interest using the default {@link Callback} with {@link Invocable.InvocationType#NON_BLOCKING}.</p>
         * <p>When read readiness is signaled, {@link #onFillable()} or {@link #onFillInterestedFailed(Throwable)}
         * will be invoked.</p>
         * <p>This method should be used sparingly, and {@link #fillInterested(Callback)}
         * should be preferred instead, passing a {@link Callback} that specifies the {@link Invocable.InvocationType}
         * for each specific case where read interest needs to be registered.</p>         */
        @Override
        public void fillInterested()
        {
            fillInterested(_nonBlockingReadCallback);
        }

        private class NonBlockingFillableCallback extends FillableCallback
        {
            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }
        }
    }

    private class FillableCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), AbstractConnection.this);
        }
    }
}

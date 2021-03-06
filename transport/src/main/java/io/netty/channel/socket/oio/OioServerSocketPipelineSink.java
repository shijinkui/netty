/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket.oio;

import static io.netty.channel.Channels.*;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.Channel;
import io.netty.channel.ChannelEvent;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelState;
import io.netty.channel.ChannelStateEvent;
import io.netty.channel.MessageEvent;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;
import io.netty.util.internal.DeadLockProofWorker;

class OioServerSocketPipelineSink extends AbstractOioChannelSink {

    static final InternalLogger logger =
        InternalLoggerFactory.getInstance(OioServerSocketPipelineSink.class);

    private static final AtomicInteger nextId = new AtomicInteger();

    final int id = nextId.incrementAndGet();
    final Executor workerExecutor;

    OioServerSocketPipelineSink(Executor workerExecutor) {
        this.workerExecutor = workerExecutor;
    }

    @Override
    public void eventSunk(
            ChannelPipeline pipeline, ChannelEvent e) throws Exception {
        Channel channel = e.getChannel();
        if (channel instanceof OioServerSocketChannel) {
            handleServerSocket(e);
        } else if (channel instanceof OioAcceptedSocketChannel) {
            handleAcceptedSocket(e);
        }
    }

    private void handleServerSocket(ChannelEvent e) {
        if (!(e instanceof ChannelStateEvent)) {
            return;
        }

        ChannelStateEvent event = (ChannelStateEvent) e;
        OioServerSocketChannel channel =
            (OioServerSocketChannel) event.getChannel();
        ChannelFuture future = event.getFuture();
        ChannelState state = event.getState();
        Object value = event.getValue();

        switch (state) {
        case OPEN:
            if (Boolean.FALSE.equals(value)) {
                close(channel, future);
            }
            break;
        case BOUND:
            if (value != null) {
                bind(channel, future, (SocketAddress) value);
            } else {
                close(channel, future);
            }
            break;
        }
    }

    private void handleAcceptedSocket(ChannelEvent e) {
        if (e instanceof ChannelStateEvent) {
            ChannelStateEvent event = (ChannelStateEvent) e;
            OioAcceptedSocketChannel channel =
                (OioAcceptedSocketChannel) event.getChannel();
            ChannelFuture future = event.getFuture();
            ChannelState state = event.getState();
            Object value = event.getValue();

            switch (state) {
            case OPEN:
                if (Boolean.FALSE.equals(value)) {
                    OioWorker.close(channel, future);
                }
                break;
            case BOUND:
            case CONNECTED:
                if (value == null) {
                    OioWorker.close(channel, future);
                }
                break;
            case INTEREST_OPS:
                OioWorker.setInterestOps(channel, future, ((Integer) value).intValue());
                break;
            }
        } else if (e instanceof MessageEvent) {
            MessageEvent event = (MessageEvent) e;
            OioSocketChannel channel = (OioSocketChannel) event.getChannel();
            ChannelFuture future = event.getFuture();
            Object message = event.getMessage();
            OioWorker.write(channel, future, message);
        }
    }

    private void bind(
            OioServerSocketChannel channel, ChannelFuture future,
            SocketAddress localAddress) {

        boolean bound = false;
        boolean bossStarted = false;
        try {
            channel.socket.bind(localAddress, channel.getConfig().getBacklog());
            bound = true;

            future.setSuccess();
            localAddress = channel.getLocalAddress();
            fireChannelBound(channel, localAddress);

            Executor bossExecutor =
                ((OioServerSocketChannelFactory) channel.getFactory()).bossExecutor;
            DeadLockProofWorker.start(bossExecutor, new Boss(channel));
            bossStarted = true;
        } catch (Throwable t) {
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        } finally {
            if (!bossStarted && bound) {
                close(channel, future);
            }
        }
    }

    private void close(OioServerSocketChannel channel, ChannelFuture future) {
        boolean bound = channel.isBound();
        try {
            channel.socket.close();

            // Make sure the boss thread is not running so that that the future
            // is notified after a new connection cannot be accepted anymore.
            // See NETTY-256 for more information.
            channel.shutdownLock.lock();
            try {
                if (channel.setClosed()) {
                    future.setSuccess();
                    if (bound) {
                        fireChannelUnbound(channel);
                    }
                    fireChannelClosed(channel);
                } else {
                    future.setSuccess();
                }
            } finally {
                channel.shutdownLock.unlock();
            }
        } catch (Throwable t) {
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        }
    }

    private final class Boss implements Runnable {
        private final OioServerSocketChannel channel;

        Boss(OioServerSocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public void run() {
            channel.shutdownLock.lock();
            try {
                while (channel.isBound()) {
                    try {
                        Socket acceptedSocket = channel.socket.accept();
                        try {
                            ChannelPipeline pipeline =
                                channel.getConfig().getPipelineFactory().getPipeline();
                            final OioAcceptedSocketChannel acceptedChannel =
                                OioAcceptedSocketChannel.create(channel, channel.getFactory(),
                                    pipeline, OioServerSocketPipelineSink.this, acceptedSocket);
                            DeadLockProofWorker.start(
                                    workerExecutor,
                                    new OioWorker(acceptedChannel));
                        } catch (Exception e) {
                            if (logger.isWarnEnabled()) {
                                logger.warn(
                                        "Failed to initialize an accepted socket.", e);
                            }
                            try {
                                acceptedSocket.close();
                            } catch (IOException e2) {
                                if (logger.isWarnEnabled()) {
                                    logger.warn(
                                            "Failed to close a partially accepted socket.",
                                            e2);
                                }
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        // Thrown every second to stop when requested.
                    } catch (Throwable e) {
                        // Do not log the exception if the server socket was closed
                        // by a user.
                        if (!channel.socket.isBound() || channel.socket.isClosed()) {
                            break;
                        }
                        
                        if (logger.isWarnEnabled()) {
                            logger.warn(
                                    "Failed to accept a connection.", e);
                        }

                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e1) {
                            // Ignore
                        }
                    }
                }
            } finally {
                channel.shutdownLock.unlock();
            }
        }
    }
}

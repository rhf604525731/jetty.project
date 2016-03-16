//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class HttpClientTransportOverHTTP2Test extends AbstractTest
{
    @Test
    public void testPropertiesAreForwarded() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), null);
        Executor executor = new QueuedThreadPool();
        httpClient.setExecutor(executor);
        httpClient.setConnectTimeout(13);
        httpClient.setIdleTimeout(17);

        httpClient.start();

        Assert.assertTrue(http2Client.isStarted());
        Assert.assertSame(httpClient.getExecutor(), http2Client.getExecutor());
        Assert.assertSame(httpClient.getScheduler(), http2Client.getScheduler());
        Assert.assertSame(httpClient.getByteBufferPool(), http2Client.getByteBufferPool());
        Assert.assertEquals(httpClient.getConnectTimeout(), http2Client.getConnectTimeout());
        Assert.assertEquals(httpClient.getIdleTimeout(), http2Client.getIdleTimeout());

        httpClient.stop();

        Assert.assertTrue(http2Client.isStopped());
    }

    @Test
    public void testRequestAbortSendsResetFrame() throws Exception
    {
        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        resetLatch.countDown();
                    }
                };
            }
        });

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .onRequestCommit(request -> request.abort(new Exception("explicitly_aborted_by_test")))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testResponseAbortSendsResetFrame() throws Exception
    {
        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                stream.headers(new HeadersFrame(stream.getId(), metaData, null, false), new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        ByteBuffer data = ByteBuffer.allocate(1024);
                        stream.data(new DataFrame(stream.getId(), data, false), NOOP);
                    }
                });

                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        resetLatch.countDown();
                    }
                };
            }
        });

        try
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .onResponseContent((response, buffer) -> response.abort(new Exception("explicitly_aborted_by_test")))
                    .send();
            Assert.fail();
        }
        catch (ExecutionException x)
        {
            Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRequestHasHTTP2Version() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                HttpVersion version = HttpVersion.fromString(request.getProtocol());
                response.setStatus(version == HttpVersion.HTTP_2 ? HttpStatus.OK_200 : HttpStatus.INTERNAL_SERVER_ERROR_500);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .onRequestBegin(request ->
                {
                    if (request.getVersion() != HttpVersion.HTTP_2)
                        request.abort(new Exception("Not a HTTP/2 request"));
                })
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testLastStreamId() throws Exception
    {
        prepareServer(new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, 1);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                if (HttpMethod.HEAD.is(request.getMethod()))
                {
                    stream.getSession().close(ErrorCode.REFUSED_STREAM_ERROR.code, null, Callback.NOOP);
                }
                else
                {
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                    stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                }
                return null;
            }
        }));
        server.start();

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger lastStream = new AtomicInteger();
        client = new HttpClient(new HttpClientTransportOverHTTP2(new HTTP2Client())
        {
            @Override
            protected void onClose(HttpConnectionOverHTTP2 connection, GoAwayFrame frame)
            {
                super.onClose(connection, frame);
                lastStream.set(frame.getLastStreamId());
                latch.countDown();
            }
        }, null);
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.start();

        // Prime the connection to allow client and server prefaces to be exchanged.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .path("/zero")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        org.eclipse.jetty.client.api.Request request = client.newRequest("localhost", connector.getLocalPort())
                .method(HttpMethod.HEAD)
                .path("/one");
        request.send(result ->
        {
            if (result.isFailed())
                latch.countDown();
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        Stream stream = (Stream)request.getAttributes().get(HTTP2Stream.class.getName());
        Assert.assertNotNull(stream);
        Assert.assertEquals(lastStream.get(), stream.getId());
    }

    @Ignore
    @Test
    public void testExternalServer() throws Exception
    {
        HTTP2Client http2Client = new HTTP2Client();
        SslContextFactory sslContextFactory = new SslContextFactory();
        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), sslContextFactory);
        Executor executor = new QueuedThreadPool();
        httpClient.setExecutor(executor);

        httpClient.start();

//        ContentResponse response = httpClient.GET("https://http2.akamai.com/");
        ContentResponse response = httpClient.GET("https://webtide.com/");

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        httpClient.stop();
    }
}

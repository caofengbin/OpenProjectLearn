/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http;

import java.io.IOException;
import java.net.ProtocolException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/**
 * This is the last interceptor in the chain. It makes a network call to the server.
 * 服务器发起网络请求，接收服务器的响应
 */
public final class CallServerInterceptor implements Interceptor {

    private final boolean forWebSocket;

    public CallServerInterceptor(boolean forWebSocket) {
        this.forWebSocket = forWebSocket;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // 5个非常重要的变量
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        HttpCodec httpCodec = realChain.httpStream();
        StreamAllocation streamAllocation = realChain.streamAllocation();
        RealConnection connection = (RealConnection) realChain.connection();                        // 抽象连接的具体实现
        Request request = realChain.request();

        // 发送请求的时间戳
        long sentRequestMillis = System.currentTimeMillis();

        realChain.eventListener().requestHeadersStart(realChain.call());
        // 核心方法1：向Socket当中写入请求头部信息
        httpCodec.writeRequestHeaders(request);
        realChain.eventListener().requestHeadersEnd(realChain.call(), request);

        Response.Builder responseBuilder = null;
        if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
            // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
            // Continue" response before transmitting the request body. If we don't get that, return
            // what we did get (such as a 4xx response) without ever transmitting the request body.
            // 特殊处理流程
            if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
                httpCodec.flushRequest();
                realChain.eventListener().responseHeadersStart(realChain.call());
                responseBuilder = httpCodec.readResponseHeaders(true);
            }

            if (responseBuilder == null) {
                // Write the request body if the "Expect: 100-continue" expectation was met.
                realChain.eventListener().requestBodyStart(realChain.call());
                long contentLength = request.body().contentLength();
                CountingSink requestBodyOut =
                        new CountingSink(httpCodec.createRequestBody(request, contentLength));
                BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);

                // 核心方法2：向Socket中写入body信息
                request.body().writeTo(bufferedRequestBody);
                bufferedRequestBody.close();
                realChain.eventListener()
                        .requestBodyEnd(realChain.call(), requestBodyOut.successfulCount);
            } else if (!connection.isMultiplexed()) {
                // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
                // from being reused. Otherwise we're still obligated to transmit the request body to
                // leave the connection in a consistent state.
                streamAllocation.noNewStreams();
            }
        }

        // 核心方法3：表明完成了整个HTTP网络请求的写入工作
        httpCodec.finishRequest();

        if (responseBuilder == null) {
            realChain.eventListener().responseHeadersStart(realChain.call());
            responseBuilder = httpCodec.readResponseHeaders(false);
        }

        // 下面是读取响应的部分
        Response response = responseBuilder
                .request(request)
                .handshake(streamAllocation.connection().handshake())
                .sentRequestAtMillis(sentRequestMillis)
                .receivedResponseAtMillis(System.currentTimeMillis())
                .build();

        int code = response.code();
        if (code == 100) {
            // server sent a 100-continue even though we did not request one.
            // try again to read the actual response
            // 核心方法4：读取HTTP响应的头部信息
            responseBuilder = httpCodec.readResponseHeaders(false);

            response = responseBuilder
                    .request(request)
                    .handshake(streamAllocation.connection().handshake())
                    .sentRequestAtMillis(sentRequestMillis)
                    .receivedResponseAtMillis(System.currentTimeMillis())
                    .build();

            code = response.code();
        }

        realChain.eventListener()
                .responseHeadersEnd(realChain.call(), response);

        if (forWebSocket && code == 101) {
            // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
            response = response.newBuilder()
                    .body(Util.EMPTY_RESPONSE)
                    .build();
        } else {
            // 核心方法5：读取网络响应的body信息，创建response
            response = response.newBuilder()
                    .body(httpCodec.openResponseBody(response))
                    .build();
        }

        if ("close".equalsIgnoreCase(response.request().header("Connection"))
                || "close".equalsIgnoreCase(response.header("Connection"))) {
            // 关闭流的操作
            streamAllocation.noNewStreams();
        }

        if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
            throw new ProtocolException(
                    "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
        }

        return response;
    }

    static final class CountingSink extends ForwardingSink {
        long successfulCount;

        CountingSink(Sink delegate) {
            super(delegate);
        }

        @Override
        public void write(Buffer source, long byteCount) throws IOException {
            super.write(source, byteCount);
            successfulCount += byteCount;
        }
    }
}

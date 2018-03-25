/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.RealInterceptorChain;

/**
 * Opens a connection to the target server and proceeds to the next interceptor.
 * 网络连接拦截器
 * 打开与服务器之间的连接，正式开启网络请求
 */
public final class ConnectInterceptor implements Interceptor {

    public final OkHttpClient client;

    public ConnectInterceptor(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        RealInterceptorChain realChain = (RealInterceptorChain) chain;
        Request request = realChain.request();

        // 1.建立HTTP请求所需要的核心StreamAllocation，在RetryAndFollowUpInterceptor中创建的
        StreamAllocation streamAllocation = realChain.streamAllocation();

        // We need the network to satisfy this request. Possibly for validating a conditional GET.
        boolean doExtensiveHealthChecks = !request.method().equals("GET");

        // 2.初始化HttpCodec
        HttpCodec httpCodec = streamAllocation.newStream(client, chain, doExtensiveHealthChecks);

        // 3.获取RealConnection，非常关键，用来进行实际网络IO传输的
        RealConnection connection = streamAllocation.connection();

        // 注意这里创建出来的三个核心的对象
        return realChain.proceed(request, streamAllocation, httpCodec, connection);
    }

}

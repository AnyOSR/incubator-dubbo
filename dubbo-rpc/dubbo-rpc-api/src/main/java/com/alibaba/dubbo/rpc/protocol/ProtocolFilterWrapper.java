/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.protocol;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.List;

/**
 * ListenerProtocol
 */
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    private static <T> Invoker<T> buildInvokerChain(final Invoker<T> invoker, String key, String group) {
        Invoker<T> last = invoker;
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(invoker.getUrl(), key, group);
        if (!filters.isEmpty()) {
            //这里为什么从最后开始遍历？
            //因为先加入到chain中的最后调用，要想按照书写顺序调用chain，就要倒着遍历filter
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                final Invoker<T> next = last;
                final Integer integer = new Integer(i);
                last = new Invoker<T>() {

                    //闭包？
                    //怎么将这个链调用连接起来，每个filter的实现的invoke方法的最后，都必须调用next.invoke(invocation)？
                    //刚开始生成了一个匿名的Invoker对象 $invoke0，这个 $invoke0 的invoke方法，实际上调用了最后一个filter对象的invoke方法，该方法的第一个参数为最外层传进来的invoke对象
                    //然后又生成了一个匿名的Invoker对象 $invoke1，这个 $invoke1 的invoke方法，实际上调用了倒数第二个filter对象的invoke方法，该方法的第一个参数是上次生成的匿名对象 $invoke0
                    //........一次类推  最后返回一个匿名的Invoke实例 $invoke(filters.size()-1)
                    //当调用 $invoke(filters.size()-1) 的invoke方法时，会调用第一个filter的invoke方法，而$(filters.size()-1)实例invoke方法的第一个参数是$invoke(filters.size()-2)
                    //当调用 $invoke1 的invoke方法时，会调用倒数第二个filter对象的invoke方法，该方法的第一个参数是匿名对象 $invoke0
                    //当调用 $invoke0 的invoke方法时，会调用倒数第一个filter对象的invoke方法，该方法的第一个参数是最外层对象 invoke
                    //为了这个调用链被调用下去，在filter实现的invoke(Invoke v1,Invocation V2 )方面里面，最后必须手动调用v1.invoke(v2)
                    //关键在于两部分 怎么去调用每个filter的invoke方法，以及怎么将这个调用传递下去
                    //invoke的invoke方法回去调用 filter实现 的invoke(Invoker<?> invoker, Invocation invocation)方法，在这个方法里面可以写自己filter的逻辑
                    //每一个invoke对象的invoke方法调用 都会引发一个filter实现的invoke方法被调用(invoke对象的invoke方法调用 等效于 filter实现的invoke方法被调用)
                    //而为了这个调用能传递下去，引发下一个filter的调用(即invoke的invoke方法被调用)，只能在filter的实现里面去触发，可以抽象成一个模式A B
                    //不需要next指针 对外表现还是一个invoke，而不是数组 入参类型没有和返回类型相同的

                    //调试时，生成的匿名Invoke竟然直接可见filter和next属性  这么骚气。。
                    @Override
                    public Result invoke(Invocation invocation) throws RpcException {
                        System.out.println("正在调用第" + integer.toString()+"个filter");
                        return filter.invoke(next, invocation);
                    }

                    //都是最外层的invoke，且invoke没有被赋值过
                    //生成的匿名Invoke 直接可见最外层的invoke(持有最外层invoke的引用)，骚浪贱啊。。
                    @Override
                    public Class<T> getInterface() {
                        return invoker.getInterface();
                    }

                    @Override
                    public URL getUrl() {
                        return invoker.getUrl();
                    }

                    @Override
                    public boolean isAvailable() {
                        return invoker.isAvailable();
                    }

                    @Override
                    public void destroy() {
                        invoker.destroy();
                    }

                    @Override
                    public String toString() {
                        return invoker.toString();
                    }
                };
            }
        }
        return last;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        if (Constants.REGISTRY_PROTOCOL.equals(invoker.getUrl().getProtocol())) {
            return protocol.export(invoker);
        }
        return protocol.export(buildInvokerChain(invoker, Constants.SERVICE_FILTER_KEY, Constants.PROVIDER));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        if (Constants.REGISTRY_PROTOCOL.equals(url.getProtocol())) {
            return protocol.refer(type, url);
        }
        return buildInvokerChain(protocol.refer(type, url), Constants.REFERENCE_FILTER_KEY, Constants.CONSUMER);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

}

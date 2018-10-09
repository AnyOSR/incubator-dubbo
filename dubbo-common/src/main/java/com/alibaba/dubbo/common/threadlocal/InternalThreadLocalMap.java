/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.alibaba.dubbo.common.threadlocal;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The internal data structure that stores the threadLocal variables for Netty and all {@link InternalThread}s.
 * Note that this class is for internal use only. Use {@link InternalThread}
 * unless you know what you are doing.
 */
public final class InternalThreadLocalMap {

    private Object[] indexedVariables;

    //如果当前thread不是InternalThread，则用这个作为当前thread.threadLocals里某一个entry的key
    //注意，所有原生Thread里所有的key都为slowThreadLocalMap
    private static ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<InternalThreadLocalMap>();

    //类似一个全局计数器
    private static final AtomicInteger nextIndex = new AtomicInteger();
    //indexedVariables的某个index下未设置元素的标志
    public static final Object UNSET = new Object();

    //static方法
    //如果当前线程是原生线程，则直接获取InternalThreadLocalMap.slowThreadLocalMap对应的值
    //如果当前线程是InternalThread，则获取InternalThread.threadLocalMap
    public static InternalThreadLocalMap getIfSet() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            return ((InternalThread) thread).threadLocalMap();
        }
        return slowThreadLocalMap.get();
    }

    //static方法
    //如果当前线程是InternalThread，则获取当前线程的threadLocalMap，如果还没有设置，则生成并返回
    //如果是原生java线程，则根据slowThreadLocalMap(key)获取线程局部变量的值，如果还没有设置，则生成并返回
    public static InternalThreadLocalMap get() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            return fastGet((InternalThread) thread);
        }
        return slowGet();
    }

    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            ((InternalThread) thread).setThreadLocalMap(null);
        } else {
            slowThreadLocalMap.remove();
        }
    }

    public static void destroy() {
        slowThreadLocalMap = null;
    }

    public static int nextVariableIndex() {
        int index = nextIndex.getAndIncrement();
        if (index < 0) {
            nextIndex.decrementAndGet();
            throw new IllegalStateException("Too many thread-local indexed variables");
        }
        return index;
    }

    public static int lastVariableIndex() {
        return nextIndex.get() - 1;
    }

    //私有构造方法，创建一个32长度的object数组，并用UNSET填充
    //可以用于判定某一个index下是否已经设置了元素
    private InternalThreadLocalMap() {
        indexedVariables = newIndexedVariableTable();
    }

    //直接根据索引去随机检索
    public Object indexedVariable(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length ? lookup[index] : UNSET;
    }

    /**
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    //设置某一个index上的object
    //如果之前index的元素没有被设置，则返回true，否则返回false
    public boolean setIndexedVariable(int index, Object value) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {   //index == lookup.length 默认32
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;
            return v;
        } else {
            return UNSET;
        }
    }

    //返回设置了多少元素
    public int size() {
        int count = 0;
        for (Object o : indexedVariables) {
            if (o != UNSET) {
                ++count;
            }
        }

        //the fist element in `indexedVariables` is a set to keep all the InternalThreadLocal to remove
        //look at method `addToVariablesToRemove`
        return count - 1;
    }

    private static Object[] newIndexedVariableTable() {
        Object[] array = new Object[32];
        Arrays.fill(array, UNSET);
        return array;
    }

    private static InternalThreadLocalMap fastGet(InternalThread thread) {
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;
    }

    //针对于原生java线程
    private static InternalThreadLocalMap slowGet() {
        //拿到对应的key，所有的原生java线程的 线程局部变量的key都为InternalThreadLocalMap.slowThreadLocalMap
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = InternalThreadLocalMap.slowThreadLocalMap;
        //拿到当前线程对应于InternalThreadLocalMap.slowThreadLocalMap的值
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            //如果没有设置，则生成一个InternalThreadLocalMap并set，最后返回
            ret = new InternalThreadLocalMap();
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    //扩容
    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = index;
        newCapacity |= newCapacity >>> 1;
        newCapacity |= newCapacity >>> 2;
        newCapacity |= newCapacity >>> 4;
        newCapacity |= newCapacity >>> 8;
        newCapacity |= newCapacity >>> 16;
        newCapacity++;
        //不直接向左移一位，而采用这种方式的原因在于
        //假如newCapacity不为2的整数倍时，直接向左移一位，相当于直接乘以了2，例如5*2=10
        //采用以上方式时，得到的新的newCapacity总是2的整数倍，若oldCapacity为5，则新的newCapacity为8

        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;
        indexedVariables = newArray;
    }
}

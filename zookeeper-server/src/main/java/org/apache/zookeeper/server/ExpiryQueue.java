/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.zookeeper.common.Time;

/**
 * ExpiryQueue tracks elements in time sorted fixed duration buckets.
 * It's used by SessionTrackerImpl to expire sessions and NIOServerCnxnFactory
 * to expire connections.
 */
public class ExpiryQueue<E> {

    private final ConcurrentHashMap<E, Long> elemMap = new ConcurrentHashMap<E, Long>();
    /**
     * The maximum number of buckets is equal to max timeout/expirationInterval,
     * so the expirationInterval should not be too small compared to the
     * max timeout that this expiry queue needs to maintain.
     */
    private final ConcurrentHashMap<Long, Set<E>> expiryMap = new ConcurrentHashMap<Long, Set<E>>();

    private final AtomicLong nextExpirationTime = new AtomicLong(); //下一次session过期的时间戳
    private final int expirationInterval; // 轮询时间间隔

    public ExpiryQueue(int expirationInterval) {
        this.expirationInterval = expirationInterval; // 过期检查时间间隔等于tickTime
        nextExpirationTime.set(roundToNextInterval(Time.currentElapsedTime())); // 下次检查的时间点
    }

    private long roundToNextInterval(long time) {
        return (time / expirationInterval + 1) * expirationInterval;
    }

    /**
     * Removes element from the queue.
     * @param elem  element to remove
     * @return time at which the element was set to expire, or null if
     *              it wasn't present
     */
    public Long remove(E elem) {
        Long expiryTime = elemMap.remove(elem);
        if (expiryTime != null) {
            Set<E> set = expiryMap.get(expiryTime);
            if (set != null) {
                set.remove(elem);
                // We don't need to worry about removing empty sets,
                // they'll eventually be removed when they expire.
            }
        }
        return expiryTime;
    }

    /**
     * 1.client与server交互时被调用2.server启动恢复磁盘数据被调用
     * Adds or updates expiration time for element in queue, rounding the
     * timeout to the expiry interval bucketed used by this queue.
     * @param elem     element to add/update
     * @param timeout  timout in milliseconds
     * @return time at which the element is now set to expire if
     *                 changed, or null if unchanged
     */
    public Long update(E elem, int timeout) {
        Long prevExpiryTime = elemMap.get(elem); // map中保存的session过期时间 key为session
        long now = Time.currentElapsedTime();
        Long newExpiryTime = roundToNextInterval(now + timeout); //新的过期时间

        if (newExpiryTime.equals(prevExpiryTime)) { // 过期时间没有改变，还在之前的set中
            // No change, so nothing to update
            return null;
        }
//        过期时间改变，需要从原过期时间set中删除session，新的过期时间set中添加session
        // First add the elem to the new expiry time bucket in expiryMap.
        Set<E> set = expiryMap.get(newExpiryTime); //获取newExpiryTime过期时间的set
        if (set == null) {
            // Construct a ConcurrentHashSet using a ConcurrentHashMap
            set = Collections.newSetFromMap(new ConcurrentHashMap<E, Boolean>()); //set为空 新建set
            // Put the new set in the map, but only if another thread
            // hasn't beaten us to it
            Set<E> existingSet = expiryMap.putIfAbsent(newExpiryTime, set);
            if (existingSet != null) {
                set = existingSet;
            }
        }
        set.add(elem); // 添加当前session

        // Map the elem to the new expiry time. If a different previous
        // mapping was present, clean up the previous expiry bucket.
        prevExpiryTime = elemMap.put(elem, newExpiryTime); //session 最新过期时间放到map中
        if (prevExpiryTime != null && !newExpiryTime.equals(prevExpiryTime)) { //之前已有过期时间，且与当前的不同
            Set<E> prevSet = expiryMap.get(prevExpiryTime); //获取之前会话set
            if (prevSet != null) {
                prevSet.remove(elem); // 删除session
            }
        }
        return newExpiryTime;
    }

    /**
     * @return milliseconds until next expiration time, or 0 if has already past
     */
    public long getWaitTime() {
        long now = Time.currentElapsedTime();
        long expirationTime = nextExpirationTime.get();
        return now < expirationTime ? (expirationTime - now) : 0L;
    }

    /**
     * Remove the next expired set of elements from expireMap. This method needs
     * to be called frequently enough by checking getWaitTime(), otherwise there
     * will be a backlog of empty sets queued up in expiryMap.
     *
     * @return next set of expired elements, or an empty set if none are
     *         ready
     */
    public Set<E> poll() {
        long now = Time.currentElapsedTime(); // 获取当前时间
        long expirationTime = nextExpirationTime.get(); // 获取下次清理时间
        if (now < expirationTime) { // 当前时间小于下次清理时间
            return Collections.emptySet(); // 返回空
        }

        Set<E> set = null;
        long newExpirationTime = expirationTime + expirationInterval; // 下一次清理时间
        if (nextExpirationTime.compareAndSet(expirationTime, newExpirationTime)) { //cas设置
            set = expiryMap.remove(expirationTime); //当前时间从过期时间set中移除
        }
        if (set == null) {
            return Collections.emptySet();
        }
        return set;
    }

    public void dump(PrintWriter pwriter) {
        pwriter.print("Sets (");
        pwriter.print(expiryMap.size());
        pwriter.print(")/(");
        pwriter.print(elemMap.size());
        pwriter.println("):");
        ArrayList<Long> keys = new ArrayList<Long>(expiryMap.keySet());
        Collections.sort(keys);
        for (long time : keys) {
            Set<E> set = expiryMap.get(time);
            if (set != null) {
                pwriter.print(set.size());
                pwriter.print(" expire at ");
                pwriter.print(Time.elapsedTimeToDate(time));
                pwriter.println(":");
                for (E elem : set) {
                    pwriter.print("\t");
                    pwriter.println(elem.toString());
                }
            }
        }
    }

    /**
     * Returns an unmodifiable view of the expiration time -&gt; elements mapping.
     */
    public Map<Long, Set<E>> getExpiryMap() {
        return Collections.unmodifiableMap(expiryMap);
    }

}


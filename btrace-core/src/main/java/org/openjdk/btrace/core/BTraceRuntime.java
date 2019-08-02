/*
 * Copyright (c) 2008, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.openjdk.btrace.core;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.openjdk.btrace.core.aggregation.Aggregation;
import org.openjdk.btrace.core.aggregation.AggregationFunction;
import org.openjdk.btrace.core.aggregation.AggregationKey;
import org.openjdk.btrace.core.comm.Command;
import org.openjdk.btrace.core.comm.GridDataCommand;
import org.openjdk.btrace.core.comm.NumberDataCommand;
import org.openjdk.btrace.core.comm.NumberMapDataCommand;
import org.openjdk.btrace.core.comm.StringMapDataCommand;
import org.openjdk.btrace.core.types.AnyType;
import org.openjdk.btrace.core.types.BTraceCollection;
import org.openjdk.btrace.core.types.BTraceDeque;
import org.openjdk.btrace.core.types.BTraceMap;
import sun.misc.Perf;
import sun.misc.Unsafe;
import sun.reflect.Reflection;
import sun.security.action.GetPropertyAction;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.MonitorInfo;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public final class BTraceRuntime {
    public static final String CMD_QUEUE_LIMIT_KEY = "org.openjdk.btrace.core.cmdQueueLimit";
    private static final boolean messageTimestamp = false;
    private static final String LINE_SEPARATOR;
    // perf counter variability - we always variable variability
    private static final int V_Variable = 3;
    // perf counter units
    private static final int V_None = 1;
    private static final int V_String = 5;
    private static final int PERF_STRING_LIMIT = 256;
    // the number of stack frames taking a thread dump adds
    private static final int THRD_DUMP_FRAMES = 1;
    private static final String HOTSPOT_BEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";
    // we need Unsafe to load BTrace class bytes as
    // bootstrap class
    private static volatile Unsafe unsafe = null;
    private static Properties dotWriterProps;
    // are we running with DTrace support enabled?
    private static boolean dtraceEnabled;
    private static boolean isNewerThan8 = false;
    private static volatile BTraceRuntimeAccessor rtAccessor = new BTraceRuntimeAccessor() {
        @Override
        public BTraceRuntimeImpl getRt() {
            return null;
        }
    };
    // jvmstat related stuff
    // to read and write perf counters
    private static volatile Perf perf;
    // performance counters created by this client
    private static Map<String, ByteBuffer> counters = new HashMap<>();
    // Few MBeans used to implement certain built-in functions
    private static volatile HotSpotDiagnosticMXBean hotspotMBean;
    private static volatile MemoryMXBean memoryMBean;
    private static volatile RuntimeMXBean runtimeMBean;
    private static volatile ThreadMXBean threadMBean;
    private static volatile List<GarbageCollectorMXBean> gcBeanList;
    private static volatile List<MemoryPoolMXBean> memPoolList;
    private static volatile OperatingSystemMXBean operatingSystemMXBean;
    private static String INDENT = "    ";

    static {
        try {
            Reflection.class.getMethod("getCallerClass");
            isNewerThan8 = true;
        } catch (NoSuchMethodException | SecurityException ex) {
            // ignore
        }
        LINE_SEPARATOR = System.getProperty("line.separator");
    }

    private BTraceRuntime() {
    }

    private static BTraceRuntimeImpl getRt() {
        BTraceRuntimeImpl rt = rtAccessor.getRt();
        return rt;
    }

    public static long parseLong(String value, long deflt) {
        if (value == null) {
            return deflt;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return deflt;
        }
    }

    public static int parseInt(String value, int deflt) {
        if (value == null) {
            return deflt;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return deflt;
        }
    }


    // The following constants are copied from VM code
    // for jvmstat.

    public static Unsafe initUnsafe() {
        try {
            if (unsafe == null) {
                unsafe = Unsafe.getUnsafe();
            }
        } catch (SecurityException e) {
            DebugSupport.warning("Unable to initialize Unsafe. BTrace will not function properly");
        }
        return unsafe;
    }

    static int getInstrumentationLevel() {
        return getRt().getInstrumentationLevel();
    }

    static void setInstrumentationLevel(int level) {
        getRt().setInstrumentationLevel(level);
    }

    public static boolean enter() {
        return getRt().enter();
    }

    /**
     * Leave method is called by every probed method just
     * before the probe actions end (and actual probed
     * method continues).
     */
    public static void leave() {
        getRt().leave();
    }

    /**
     * Utility to create a new jvmstat perf counter. Called
     * by preprocessed BTrace class to create perf counter
     * for each @Export variable.
     */
    public static void newPerfCounter(String name, String desc, Object value) {
        newPerfCounter(value, name, desc);
    }

    public static void newPerfCounter(Object value, String name, String desc) {
        Perf perf = getPerf();
        char tc = desc.charAt(0);
        switch (tc) {
            case 'C':
            case 'Z':
            case 'B':
            case 'S':
            case 'I':
            case 'J':
            case 'F':
            case 'D': {
                long initValue = (value != null) ? ((Number) value).longValue() : 0L;
                ByteBuffer b = perf.createLong(name, V_Variable, V_None, initValue);
                b.order(ByteOrder.nativeOrder());
                counters.put(name, b);
            }
            break;

            case '[':
                break;
            case 'L': {
                if (desc.equals("Ljava/lang/String;")) {
                    byte[] buf;
                    if (value != null) {
                        buf = getStringBytes((String) value);
                    } else {
                        buf = new byte[PERF_STRING_LIMIT];
                        buf[0] = '\0';
                    }
                    ByteBuffer b = perf.createByteArray(name, V_Variable, V_String,
                            buf, buf.length);
                    counters.put(name, b);
                }
            }
            break;
        }
    }

    /**
     * Return the value of integer perf. counter of given name.
     */
    public static int getPerfInt(String name) {
        return (int) getPerfLong(name);
    }

    /**
     * Write the value of integer perf. counter of given name.
     */
    public static void putPerfInt(int value, String name) {
        long l = (long) value;
        putPerfLong(l, name);
    }

    /**
     * Return the value of float perf. counter of given name.
     */
    public static float getPerfFloat(String name) {
        int val = getPerfInt(name);
        return Float.intBitsToFloat(val);
    }

    /**
     * Write the value of float perf. counter of given name.
     */
    public static void putPerfFloat(float value, String name) {
        int i = Float.floatToRawIntBits(value);
        putPerfInt(i, name);
    }

    /**
     * Return the value of long perf. counter of given name.
     */
    public static long getPerfLong(String name) {
        ByteBuffer b = counters.get(name);
        synchronized (b) {
            long l = b.getLong();
            b.rewind();
            return l;
        }
    }

    /**
     * Write the value of float perf. counter of given name.
     */
    public static void putPerfLong(long value, String name) {
        ByteBuffer b = counters.get(name);
        synchronized (b) {
            b.putLong(value);
            b.rewind();
        }
    }

    /**
     * Return the value of double perf. counter of given name.
     */
    public static double getPerfDouble(String name) {
        long val = getPerfLong(name);
        return Double.longBitsToDouble(val);
    }

    /**
     * write the value of double perf. counter of given name.
     */
    public static void putPerfDouble(double value, String name) {
        long l = Double.doubleToRawLongBits(value);
        putPerfLong(l, name);
    }

    /**
     * Return the value of String perf. counter of given name.
     */
    public static String getPerfString(String name) {
        ByteBuffer b = counters.get(name);
        byte[] buf = new byte[b.limit()];
        byte t = (byte) 0;
        int i = 0;
        synchronized (b) {
            while ((t = b.get()) != '\0') {
                buf[i++] = t;
            }
            b.rewind();
        }
        try {
            return new String(buf, 0, i, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            // ignore, UTF-8 encoding is always known
        }
        return "";
    }

    /**
     * Write the value of float perf. counter of given name.
     */
    public static void putPerfString(String value, String name) {
        ByteBuffer b = counters.get(name);
        byte[] v = getStringBytes(value);
        synchronized (b) {
            b.put(v);
            b.rewind();
        }
    }

    /**
     * Handles exception from BTrace probe actions.
     */
    public static void handleException(Throwable th) {
        getRt().handleException(th);
    }

    public static String safeStr(Object obj) {
        if (obj == null) {
            return "null";
        } else if (obj instanceof String) {
            return (String) obj;
        } else if (obj.getClass().getClassLoader() == null) {
            try {
                String str = obj.toString();
                return str;
            } catch (NullPointerException e) {
                // NPE can be thrown from inside the toString() method we have no control over
                return "null";
            } catch (Throwable e) {
                e.printStackTrace();
                return "error";
            }
        } else {
            return identityStr(obj);
        }
    }

    // package-private interface to BTraceUtils class.

    private static String identityStr(Object obj) {
        int hashCode = java.lang.System.identityHashCode(obj);
        return obj.getClass().getName() + "@" + Integer.toHexString(hashCode);
    }

    static int speculation() {
        return getRt().speculation();
    }

    static void speculate(int id) {
        getRt().speculate(id);
    }

    static void discard(int id) {
        getRt().discard(id);
    }

    static void commit(int id) {
        getRt().commit(id);
    }

    /**
     * Indicates whether two given objects are "equal to" one another.
     * For bootstrap classes, returns the result of calling Object.equals()
     * override. For non-bootstrap classes, the reference identity comparison
     * is done.
     *
     * @param obj1 first object to compare equality
     * @param obj2 second object to compare equality
     * @return <code>true</code> if the given objects are equal;
     * <code>false</code> otherwise.
     */
    static boolean compare(Object obj1, Object obj2) {
        if (obj1 instanceof String) {
            return obj1.equals(obj2);
        } else if (obj1.getClass().getClassLoader() == null) {
            if (obj2 == null || obj2.getClass().getClassLoader() == null) {
                return obj1.equals(obj2);
            } // else fall through..
        }
        return obj1 == obj2;
    }

    // BTrace map functions
    static <K, V> Map<K, V> newHashMap() {
        return new BTraceMap(new HashMap<K, V>());
    }

    static <K, V> Map<K, V> newWeakMap() {
        return new BTraceMap(new WeakHashMap<K, V>());
    }

    static <V> Deque<V> newDeque() {
        return new BTraceDeque<>(new ArrayDeque<V>());
    }

    static Appendable newStringBuilder(boolean threadSafe) {
        return threadSafe ? new StringBuffer() : new StringBuilder();
    }

    static Appendable newStringBuilder() {
        return newStringBuilder(false);
    }

    static <E> int size(Collection<E> coll) {
        if (coll instanceof BTraceCollection || coll.getClass().getClassLoader() == null) {
            return coll.size();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <E> boolean isEmpty(Collection<E> coll) {
        if (coll instanceof BTraceCollection || coll.getClass().getClassLoader() == null) {
            return coll.isEmpty();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static <E> boolean contains(Collection<E> coll, Object obj) {
        if (coll instanceof BTraceCollection || coll.getClass().getClassLoader() == null) {
            for (E e : coll) {
                if (compare(e, obj)) {
                    return true;
                }
            }
            return false;
        } else {
            throw new IllegalArgumentException();
        }
    }

    static <E> Object[] toArray(Collection<E> collection) {
        if (collection == null) {
            return new Object[0];
        } else {
            return collection.toArray();
        }
    }

    static <K, V> V get(Map<K, V> map, K key) {
        if (map instanceof BTraceMap ||
                map.getClass().getClassLoader() == null) {
            return map.get(key);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static <K, V> boolean containsKey(Map<K, V> map, K key) {
        if (map instanceof BTraceMap ||
                map.getClass().getClassLoader() == null) {
            return map.containsKey(key);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static <K, V> boolean containsValue(Map<K, V> map, V value) {
        if (map instanceof BTraceMap ||
                map.getClass().getClassLoader() == null) {
            return map.containsValue(value);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static <K, V> V put(Map<K, V> map, K key, V value) {
        if (map instanceof BTraceMap) {
            return map.put(key, value);
        } else {
            throw new IllegalArgumentException("not a btrace map");
        }
    }

    static <K, V> V remove(Map<K, V> map, K key) {
        if (map instanceof BTraceMap) {
            return map.remove(key);
        } else {
            throw new IllegalArgumentException("not a btrace map");
        }
    }

    static <K, V> void clear(Map<K, V> map) {
        if (map instanceof BTraceMap) {
            map.clear();
        } else {
            throw new IllegalArgumentException("not a btrace map");
        }
    }

    static <K, V> int size(Map<K, V> map) {
        if (map instanceof BTraceMap ||
                map.getClass().getClassLoader() == null) {
            return map.size();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static <K, V> boolean isEmpty(Map<K, V> map) {
        if (map instanceof BTraceMap ||
                map.getClass().getClassLoader() == null) {
            return map.isEmpty();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static <K, V> void putAll(Map<K, V> src, Map<K, V> dst) {
        dst.putAll(src);
    }

    static <K, V> void copy(Map<K, V> src, Map<K, V> dst) {
        dst.clear();
        dst.putAll(src);
    }

    static void printMap(Map map) {
        if (map instanceof BTraceMap ||
                map.getClass().getClassLoader() == null) {
            synchronized (map) {
                Map<String, String> m = new HashMap<>();
                Set<Map.Entry<Object, Object>> entries = map.entrySet();
                for (Map.Entry<Object, Object> e : entries) {
                    m.put(BTraceUtils.Strings.str(e.getKey()), BTraceUtils.Strings.str(e.getValue()));
                }
                printStringMap(null, m);
            }
        } else {
            print(BTraceUtils.Strings.str(map));
        }
    }

    public static <V> void push(Deque<V> queue, V value) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            queue.push(value);
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <V> void addLast(Deque<V> queue, V value) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            queue.addLast(value);
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <V> V peekFirst(Deque<V> queue) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            return queue.peekFirst();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <V> V peekLast(Deque<V> queue) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            return queue.peekLast();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <V> V removeLast(Deque<V> queue) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            return queue.removeLast();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <V> V removeFirst(Deque<V> queue) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            return queue.removeFirst();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <V> V poll(Deque<V> queue) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            return queue.poll();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <V> V peek(Deque<V> queue) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            return queue.peek();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static <V> void clear(Deque<V> queue) {
        if (queue instanceof BTraceDeque || queue.getClass().getClassLoader() == null) {
            queue.clear();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public static Appendable append(Appendable buffer, String strToAppend) {
        try {
            if (buffer != null && strToAppend != null) {
                return buffer.append(strToAppend);
            } else {
                throw new IllegalArgumentException();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static int length(Appendable buffer) {
        if (buffer != null && buffer instanceof CharSequence) {
            return ((CharSequence) buffer).length();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static void printNumber(String name, Number value) {
        getRt().send(new NumberDataCommand(name, value));
    }

    static void printNumberMap(String name, Map<String, ? extends Number> data) {
        getRt().send(new NumberMapDataCommand(name, data));
    }

    static void printStringMap(String name, Map<String, String> data) {
        getRt().send(new StringMapDataCommand(name, data));
    }

    // BTrace exit built-in function
    static void exit(int exitCode) {
        getRt().exit(exitCode);
    }

    static long sizeof(Object obj) {
        return getRt().sizeof(obj);
    }

    // BTrace command line argument functions
    static int $length() {
        return getRt().$length();
    }

    static String $(int n) {
        return getRt().$(n);
    }

    static String $(String key) {
        return getRt().$(key);
    }

    /**
     * @see BTraceUtils#instanceOf(java.lang.Object, java.lang.String)
     */
    static boolean instanceOf(Object obj, String className) {
        if (obj instanceof AnyType) {
            // the only time we can have AnyType on stack
            // is if it was passed in as a placeholder
            // for void @Return parameter value
            if (className.equalsIgnoreCase("void")) {
                return obj.equals(AnyType.VOID);
            }
            return false;
        }
        Class objClass = obj.getClass();
        ClassLoader cl = objClass.getClassLoader();
        cl = cl != null ? cl : ClassLoader.getSystemClassLoader();
        try {
            Class target = cl.loadClass(className);
            return target.isAssignableFrom(objClass);
        } catch (ClassNotFoundException e) {
            // non-existing class
            getRt().debugPrint(e);
            return false;
        }
    }

    static AtomicInteger newAtomicInteger(int initVal) {
        return new BTraceAtomicInteger(initVal);
    }

    static int get(AtomicInteger ai) {
        if (ai instanceof BTraceAtomicInteger ||
                ai.getClass().getClassLoader() == null) {
            return ai.get();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static void set(AtomicInteger ai, int i) {
        if (ai instanceof BTraceAtomicInteger) {
            ai.set(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static void lazySet(AtomicInteger ai, int i) {
        if (ai instanceof BTraceAtomicInteger) {
            ai.lazySet(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static boolean compareAndSet(AtomicInteger ai, int i, int j) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.compareAndSet(i, j);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static boolean weakCompareAndSet(AtomicInteger ai, int i, int j) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.weakCompareAndSet(i, j);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static int getAndIncrement(AtomicInteger ai) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.getAndIncrement();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static int getAndDecrement(AtomicInteger ai) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.getAndDecrement();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static int incrementAndGet(AtomicInteger ai) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.incrementAndGet();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static int decrementAndGet(AtomicInteger ai) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.decrementAndGet();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static int getAndAdd(AtomicInteger ai, int i) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.getAndAdd(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static int addAndGet(AtomicInteger ai, int i) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.addAndGet(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static int getAndSet(AtomicInteger ai, int i) {
        if (ai instanceof BTraceAtomicInteger) {
            return ai.getAndSet(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static AtomicLong newAtomicLong(long initVal) {
        return new BTraceAtomicLong(initVal);
    }

    static long get(AtomicLong al) {
        if (al instanceof BTraceAtomicLong ||
                al.getClass().getClassLoader() == null) {
            return al.get();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static void set(AtomicLong al, long i) {
        if (al instanceof BTraceAtomicLong) {
            al.set(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static void lazySet(AtomicLong al, long i) {
        if (al instanceof BTraceAtomicLong) {
            al.lazySet(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static boolean compareAndSet(AtomicLong al, long i, long j) {
        if (al instanceof BTraceAtomicLong) {
            return al.compareAndSet(i, j);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static boolean weakCompareAndSet(AtomicLong al, long i, long j) {
        if (al instanceof BTraceAtomicLong) {
            return al.weakCompareAndSet(i, j);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static long getAndIncrement(AtomicLong al) {
        if (al instanceof BTraceAtomicLong) {
            return al.getAndIncrement();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static long getAndDecrement(AtomicLong al) {
        if (al instanceof BTraceAtomicLong) {
            return al.getAndDecrement();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static long incrementAndGet(AtomicLong al) {
        if (al instanceof BTraceAtomicLong) {
            return al.incrementAndGet();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static long decrementAndGet(AtomicLong al) {
        if (al instanceof BTraceAtomicLong) {
            return al.decrementAndGet();
        } else {
            throw new IllegalArgumentException();
        }
    }

    static long getAndAdd(AtomicLong al, long i) {
        if (al instanceof BTraceAtomicLong) {
            return al.getAndAdd(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static long addAndGet(AtomicLong al, long i) {
        if (al instanceof BTraceAtomicLong) {
            return al.addAndGet(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    static long getAndSet(AtomicLong al, long i) {
        if (al instanceof BTraceAtomicLong) {
            return al.getAndSet(i);
        } else {
            throw new IllegalArgumentException();
        }
    }

    // BTrace perf counter reading functions
    static int perfInt(String name) {
        return getRt().perfInt(name);
    }

    static long perfLong(String name) {
        return getRt().perfLong(name);
    }

    static String perfString(String name) {
        return getRt().perfString(name);
    }

    // stack trace functions
    private static String stackTraceAllStr(int numFrames, boolean printWarning) {
        Set<Map.Entry<Thread, StackTraceElement[]>> traces =
                Thread.getAllStackTraces().entrySet();
        StringBuilder buf = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> t : traces) {
            buf.append(t.getKey().toString());
            buf.append(LINE_SEPARATOR);
            buf.append(LINE_SEPARATOR);
            StackTraceElement[] st = t.getValue();
            buf.append(stackTraceStr("\t", st, 0, numFrames, printWarning));
            buf.append(LINE_SEPARATOR);
        }
        return buf.toString();
    }

    static String stackTraceAllStr(int numFrames) {
        return stackTraceAllStr(numFrames, false);
    }

    static void stackTraceAll(int numFrames) {
        getRt().send(stackTraceAllStr(numFrames, true));
    }

    static String stackTraceStr(StackTraceElement[] st,
                                int strip, int numFrames) {
        return stackTraceStr(null, st, strip, numFrames, false);
    }

    static String stackTraceStr(String prefix, StackTraceElement[] st,
                                int strip, int numFrames) {
        return stackTraceStr(prefix, st, strip, numFrames, false);
    }

    private static String stackTraceStr(String prefix, StackTraceElement[] st,
                                        int strip, int numFrames, boolean printWarning) {
        strip = strip > 0 ? strip + THRD_DUMP_FRAMES : 0;
        numFrames = numFrames > 0 ? numFrames : st.length - strip;

        int limit = strip + numFrames;
        limit = limit <= st.length ? limit : st.length;

        if (prefix == null) {
            prefix = "";
        }

        StringBuilder buf = new StringBuilder();
        for (int i = strip; i < limit; i++) {
            buf.append(prefix);
            buf.append(st[i].toString());
            buf.append(LINE_SEPARATOR);
        }
        if (printWarning && limit < st.length) {
            buf.append(prefix);
            buf.append(st.length - limit);
            buf.append(" more frame(s) ...");
            buf.append(LINE_SEPARATOR);
        }
        return buf.toString();
    }

    static void stackTrace(StackTraceElement[] st,
                           int strip, int numFrames) {
        stackTrace(null, st, strip, numFrames);
    }

    static void stackTrace(String prefix, StackTraceElement[] st,
                           int strip, int numFrames) {
        getRt().send(stackTraceStr(prefix, st, strip, numFrames, true));
    }

    // print/println functions
    static void print(String str) {
        getRt().send(str);
    }

    static void println(String str) {
        getRt().send(str + LINE_SEPARATOR);
    }

    static void println() {
        getRt().send(LINE_SEPARATOR);
    }

    static String property(String name) {
        return AccessController.doPrivileged(
                new GetPropertyAction(name));
    }

    static Properties properties() {
        return AccessController.doPrivileged(
                new PrivilegedAction<Properties>() {
                    @Override
                    public Properties run() {
                        return System.getProperties();
                    }
                }
        );
    }

    static String getenv(final String name) {
        return AccessController.doPrivileged(
                new PrivilegedAction<String>() {
                    @Override
                    public String run() {
                        return System.getenv(name);
                    }
                }
        );
    }

    static Map<String, String> getenv() {
        return AccessController.doPrivileged(
                new PrivilegedAction<Map<String, String>>() {
                    @Override
                    public Map<String, String> run() {
                        return System.getenv();
                    }
                }
        );
    }

    static MemoryUsage heapUsage() {
        initMemoryMBean();
        return memoryMBean.getHeapMemoryUsage();
    }

    static MemoryUsage nonHeapUsage() {
        initMemoryMBean();
        return memoryMBean.getNonHeapMemoryUsage();
    }

    static long finalizationCount() {
        initMemoryMBean();
        return memoryMBean.getObjectPendingFinalizationCount();
    }

    static long vmStartTime() {
        initRuntimeMBean();
        return runtimeMBean.getStartTime();
    }

    static long vmUptime() {
        initRuntimeMBean();
        return runtimeMBean.getUptime();
    }

    static List<String> getInputArguments() {
        initRuntimeMBean();
        return runtimeMBean.getInputArguments();
    }

    static String getVmVersion() {
        initRuntimeMBean();
        return runtimeMBean.getVmVersion();
    }

    static boolean isBootClassPathSupported() {
        initRuntimeMBean();
        return runtimeMBean.isBootClassPathSupported();
    }

    static String getBootClassPath() {
        initRuntimeMBean();
        return runtimeMBean.getBootClassPath();
    }

    static long getThreadCount() {
        initThreadMBean();
        return threadMBean.getThreadCount();
    }

    static long getPeakThreadCount() {
        initThreadMBean();
        return threadMBean.getPeakThreadCount();
    }

    static long getTotalStartedThreadCount() {
        initThreadMBean();
        return threadMBean.getTotalStartedThreadCount();
    }

    static long getDaemonThreadCount() {
        initThreadMBean();
        return threadMBean.getDaemonThreadCount();
    }

    static long getCurrentThreadCpuTime() {
        initThreadMBean();
        threadMBean.setThreadCpuTimeEnabled(true);
        return threadMBean.getCurrentThreadCpuTime();
    }

    static long getCurrentThreadUserTime() {
        initThreadMBean();
        threadMBean.setThreadCpuTimeEnabled(true);
        return threadMBean.getCurrentThreadUserTime();
    }

    static void dumpHeap(String fileName, boolean live) {
        initHotspotMBean();
        try {
            String name = getRt().resolveFileName(fileName);
            hotspotMBean.dumpHeap(name, live);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }

    static long getTotalGcTime() {
        initGarbageCollectionBeans();
        long totalGcTime = 0;
        for (GarbageCollectorMXBean gcBean : gcBeanList) {
            totalGcTime += gcBean.getCollectionTime();
        }
        return totalGcTime;
    }

    static String getMemoryPoolUsage(String poolFormat) {
        if (poolFormat == null) {
            poolFormat = "%1$s;%2$d;%3$d;%4$d;%5$d";
        }
        Object[][] poolOutput = new Object[memPoolList.size()][5];

        StringBuilder membuffer = new StringBuilder();

        for (int i = 0; i < memPoolList.size(); i++) {
            MemoryPoolMXBean memPool = memPoolList.get(i);
            poolOutput[i][0] = memPool.getName();
            poolOutput[i][1] = memPool.getUsage().getMax();
            poolOutput[i][2] = memPool.getUsage().getUsed();
            poolOutput[i][3] = memPool.getUsage().getCommitted();
            poolOutput[i][4] = memPool.getUsage().getInit();

        }
        for (Object[] memPoolOutput : poolOutput) {
            membuffer.append(String.format(poolFormat, memPoolOutput)).append("\n");
        }

        return membuffer.toString();
    }

    static double getSystemLoadAverage() {
        initOperatingSystemBean();
        return operatingSystemMXBean.getSystemLoadAverage();
    }

    static long getProcessCPUTime() {
        initOperatingSystemBean();
        if (operatingSystemMXBean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) operatingSystemMXBean).getProcessCpuTime();
        }

        return -1;
    }

    static void serialize(Object obj, String fileName) {
        try {
            BufferedOutputStream bos = new BufferedOutputStream(
                    new FileOutputStream(getRt().resolveFileName(fileName)));
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(obj);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }

    static String toXML(Object obj) {
        return getRt().toXML(obj);
    }

    static void writeXML(Object obj, String fileName) {
        getRt().writeXML(obj, fileName);
    }

    static void writeDOT(Object obj, String fileName) {
        getRt().writeDOT(obj, fileName);
    }

    static void deadlocks(boolean stackTrace) {
        initThreadMBean();
        if (threadMBean.isSynchronizerUsageSupported()) {
            long[] tids = threadMBean.findDeadlockedThreads();
            if (tids != null && tids.length > 0) {
                ThreadInfo[] infos = threadMBean.getThreadInfo(tids, true, true);
                StringBuilder sb = new StringBuilder();
                for (ThreadInfo ti : infos) {
                    sb.append("\"").append(ti.getThreadName()).append("\"" + " Id=").append(ti.getThreadId()).append(" in ").append(ti.getThreadState());
                    if (ti.getLockName() != null) {
                        sb.append(" on lock=").append(ti.getLockName());
                    }
                    if (ti.isSuspended()) {
                        sb.append(" (suspended)");
                    }
                    if (ti.isInNative()) {
                        sb.append(" (running in native)");
                    }
                    if (ti.getLockOwnerName() != null) {
                        sb.append(INDENT).append(" owned by ").append(ti.getLockOwnerName()).append(" Id=").append(ti.getLockOwnerId());
                        sb.append(LINE_SEPARATOR);
                    }

                    if (stackTrace) {
                        // print stack trace with locks
                        StackTraceElement[] stacktrace = ti.getStackTrace();
                        MonitorInfo[] monitors = ti.getLockedMonitors();
                        for (int i = 0; i < stacktrace.length; i++) {
                            StackTraceElement ste = stacktrace[i];
                            sb.append(INDENT).append("at ").append(ste.toString());
                            sb.append(LINE_SEPARATOR);
                            for (MonitorInfo mi : monitors) {
                                if (mi.getLockedStackDepth() == i) {
                                    sb.append(INDENT).append("  - locked ").append(mi);
                                    sb.append(LINE_SEPARATOR);
                                }
                            }
                        }
                        sb.append(LINE_SEPARATOR);
                    }

                    LockInfo[] locks = ti.getLockedSynchronizers();
                    sb.append(INDENT).append("Locked synchronizers: count = ").append(locks.length);
                    sb.append(LINE_SEPARATOR);
                    for (LockInfo li : locks) {
                        sb.append(INDENT).append("  - ").append(li);
                        sb.append(LINE_SEPARATOR);
                    }
                    sb.append(LINE_SEPARATOR);
                }
                getRt().send(sb.toString());
            }
        }
    }

    static int dtraceProbe(String s1, String s2, int i1, int i2) {
        if (dtraceEnabled) {
            return dtraceProbe0(s1, s2, i1, i2);
        } else {
            return 0;
        }
    }

    // BTrace aggregation support
    static Aggregation newAggregation(AggregationFunction type) {
        return new Aggregation(type);
    }

    static AggregationKey newAggregationKey(Object... elements) {
        return new AggregationKey(elements);
    }

    static void addToAggregation(Aggregation aggregation, long value) {
        aggregation.add(value);
    }

    static void addToAggregation(Aggregation aggregation, AggregationKey key, long value) {
        aggregation.add(key, value);
    }

    static void clearAggregation(Aggregation aggregation) {
        aggregation.clear();
    }

    static void truncateAggregation(Aggregation aggregation, int count) {
        aggregation.truncate(count);
    }

    static void printAggregation(String name, Aggregation aggregation) {
        getRt().send(new GridDataCommand(name, aggregation.getData()));
    }

    static void printSnapshot(String name, Profiler.Snapshot snapshot) {
        getRt().send(new GridDataCommand(name, snapshot.getGridData()));
    }

    /**
     * Prints profiling snapshot using the provided format
     *
     * @param name     The name of the aggregation to be used in the textual output
     * @param snapshot The snapshot to print
     * @param format   The format to use. It mimics {@linkplain String#format(java.lang.String, java.lang.Object[]) } behaviour
     *                 with the addition of the ability to address the key title as a 0-indexed item
     * @see String#format(java.lang.String, java.lang.Object[])
     */
    static void printSnapshot(String name, Profiler.Snapshot snapshot, String format) {
        getRt().send(new GridDataCommand(name, snapshot.getGridData(), format));
    }

    /**
     * Precondition: Only values from the first Aggregation are printed. If the subsequent aggregations have
     * values for keys which the first aggregation does not have, these rows are ignored.
     *
     * @param name
     * @param format
     * @param aggregationArray
     */
    static void printAggregation(String name, String format, Aggregation[] aggregationArray) {
        if (aggregationArray.length > 1 && aggregationArray[0].getKeyData().size() > 1) {
            int aggregationDataSize = aggregationArray[0].getKeyData().get(0).getElements().length + aggregationArray.length;

            List<Object[]> aggregationData = new ArrayList<>();

            //Iterate through all keys in the first Aggregation and build up an array of aggregationData
            for (AggregationKey aggKey : aggregationArray[0].getKeyData()) {
                int aggDataIndex = 0;
                Object[] currAggregationData = new Object[aggregationDataSize];

                //Add the key to the from of the current aggregation Data
                for (Object obj : aggKey.getElements()) {
                    currAggregationData[aggDataIndex] = obj;
                    aggDataIndex++;
                }

                for (Aggregation agg : aggregationArray) {
                    currAggregationData[aggDataIndex] = agg.getValueForKey(aggKey);
                    aggDataIndex++;
                }

                aggregationData.add(currAggregationData);
            }

            getRt().send(new GridDataCommand(name, aggregationData, format));
        }
    }

    /**
     * Prints aggregation using the provided format
     *
     * @param name        The name of the aggregation to be used in the textual output
     * @param aggregation The aggregation to print
     * @param format      The format to use. It mimics {@linkplain String#format(java.lang.String, java.lang.Object[]) } behaviour
     *                    with the addition of the ability to address the key title as a 0-indexed item
     * @see String#format(java.lang.String, java.lang.Object[])
     */
    static void printAggregation(String name, Aggregation aggregation, String format) {
        getRt().send(new GridDataCommand(name, aggregation.getData(), format));
    }

    /**
     * @see BTraceUtils.Profiling#newProfiler()
     */
    static Profiler newProfiler() {
        return getRt().newProfiler();
    }

    /**
     * @see BTraceUtils.Profiling#newProfiler(int)
     */
    static Profiler newProfiler(int expectedMethodCnt) {
        return getRt().newProfiler(expectedMethodCnt);
    }

    /**
     * @see BTraceUtils.Profiling#recordEntry(Profiler, java.lang.String)
     */
    static void recordEntry(Profiler profiler, String methodName) {
        profiler.recordEntry(methodName);
    }

    // profiling related methods

    /**
     * @see BTraceUtils.Profiling#recordExit(Profiler, java.lang.String, long)
     */
    static void recordExit(Profiler profiler, String methodName, long duration) {
        profiler.recordExit(methodName, duration);
    }

    /**
     * @see BTraceUtils.Profiling#snapshot(Profiler)
     */
    static Profiler.Snapshot snapshot(Profiler profiler) {
        return profiler.snapshot();
    }

    /**
     * @see BTraceUtils.Profiling#snapshotAndReset(Profiler)
     */
    static Profiler.Snapshot snapshotAndReset(Profiler profiler) {
        return profiler.snapshot(true);
    }

    static void resetProfiler(Profiler profiler) {
        profiler.reset();
    }

    // private methods below this point
    // raise DTrace USDT probe
    private static native int dtraceProbe0(String s1, String s2, int i1, int i2);

    private static void initHotspotMBean() {
        if (hotspotMBean == null) {
            synchronized (BTraceRuntime.class) {
                if (hotspotMBean == null) {
                    hotspotMBean = getHotspotMBean();
                }
            }
        }
    }

    private static HotSpotDiagnosticMXBean getHotspotMBean() {
        try {
            return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<HotSpotDiagnosticMXBean>() {
                        @Override
                        public HotSpotDiagnosticMXBean run() throws Exception {
                            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                            Set<ObjectName> s = server.queryNames(new ObjectName(HOTSPOT_BEAN_NAME), null);
                            Iterator<ObjectName> itr = s.iterator();
                            if (itr.hasNext()) {
                                ObjectName name = itr.next();
                                HotSpotDiagnosticMXBean bean =
                                        ManagementFactory.newPlatformMXBeanProxy(server,
                                                name.toString(), HotSpotDiagnosticMXBean.class);
                                return bean;
                            } else {
                                return null;
                            }
                        }
                    });
        } catch (Exception exp) {
            throw new UnsupportedOperationException(exp);
        }
    }

    public static void initMemoryMBean() {
        if (memoryMBean == null) {
            synchronized (BTraceRuntime.class) {
                if (memoryMBean == null) {
                    memoryMBean = getMemoryMBean();
                }
            }
        }
    }

    private static MemoryMXBean getMemoryMBean() {
        try {
            return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<MemoryMXBean>() {
                        @Override
                        public MemoryMXBean run() throws Exception {
                            return ManagementFactory.getMemoryMXBean();
                        }
                    });
        } catch (Exception exp) {
            throw new UnsupportedOperationException(exp);
        }
    }

    private static void initRuntimeMBean() {
        if (runtimeMBean == null) {
            synchronized (BTraceRuntime.class) {
                if (runtimeMBean == null) {
                    runtimeMBean = getRuntimeMBean();
                }
            }
        }
    }

    private static RuntimeMXBean getRuntimeMBean() {
        try {
            return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<RuntimeMXBean>() {
                        @Override
                        public RuntimeMXBean run() throws Exception {
                            return ManagementFactory.getRuntimeMXBean();
                        }
                    });
        } catch (Exception exp) {
            throw new UnsupportedOperationException(exp);
        }
    }

    private static void initThreadMBean() {
        if (threadMBean == null) {
            synchronized (BTraceRuntime.class) {
                if (threadMBean == null) {
                    threadMBean = getThreadMBean();
                }
            }
        }
    }

    private static ThreadMXBean getThreadMBean() {
        try {
            return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<ThreadMXBean>() {
                        @Override
                        public ThreadMXBean run() throws Exception {
                            return ManagementFactory.getThreadMXBean();
                        }
                    });
        } catch (Exception exp) {
            throw new UnsupportedOperationException(exp);
        }
    }

    private static List<GarbageCollectorMXBean> getGarbageCollectionMBeans() {
        try {
            return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<List<GarbageCollectorMXBean>>() {
                        @Override
                        public List<GarbageCollectorMXBean> run() throws Exception {
                            return ManagementFactory.getGarbageCollectorMXBeans();
                        }
                    });
        } catch (Exception exp) {
            throw new UnsupportedOperationException(exp);
        }
    }

    private static void initGarbageCollectionBeans() {
        if (gcBeanList == null) {
            synchronized (BTraceRuntime.class) {
                if (gcBeanList == null) {
                    gcBeanList = getGarbageCollectionMBeans();
                }
            }
        }
    }

    private static OperatingSystemMXBean getOperatingSystemMXBean() {
        try {
            return AccessController.doPrivileged(
                    new PrivilegedExceptionAction<OperatingSystemMXBean>() {
                        @Override
                        public OperatingSystemMXBean run() throws Exception {
                            return ManagementFactory.getOperatingSystemMXBean();
                        }
                    });
        } catch (Exception exp) {
            throw new UnsupportedOperationException(exp);
        }
    }

    private static void initOperatingSystemBean() {
        if (operatingSystemMXBean == null) {
            synchronized (BTraceRuntime.class) {
                if (operatingSystemMXBean == null) {
                    operatingSystemMXBean = getOperatingSystemMXBean();
                }
            }
        }
    }

    private static Perf getPerf() {
        if (perf == null) {
            synchronized (BTraceRuntime.class) {
                if (perf == null) {
                    perf = (Perf) AccessController.doPrivileged(new Perf.GetPerfAction());
                }
            }
        }
        return perf;
    }

    private static byte[] getStringBytes(String value) {
        byte[] v = null;
        try {
            v = value.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        byte[] v1 = new byte[v.length + 1];
        System.arraycopy(v, 0, v1, 0, v.length);
        v1[v.length] = '\0';
        return v1;
    }

    public interface BTraceRuntimeImpl {
        void debugPrint(Throwable t);

        void send(String msg);

        void send(Command cmd);

        boolean enter();

        void leave();

        int getInstrumentationLevel();

        void setInstrumentationLevel(int level);

        void handleException(Throwable th);

        int speculation();

        void speculate(int id);

        void commit(int id);

        void discard(int id);

        void exit(int exitCode);

        long sizeof(Object obj);

        int $length();

        String $(int n);

        String $(String key);

        String toXML(Object obj);

        void writeXML(Object obj, String fileName);

        void writeDOT(Object obj, String fileName);

        Profiler newProfiler();

        Profiler newProfiler(int expectedMethodCnt);

        int perfInt(String name);

        long perfLong(String name);

        String perfString(String name);

        String resolveFileName(String name);
    }

    public interface BTraceRuntimeAccessor {
        BTraceRuntimeImpl getRt();
    }

    private final static class BTraceAtomicInteger extends AtomicInteger {
        BTraceAtomicInteger(int initVal) {
            super(initVal);
        }
    }

    private final static class BTraceAtomicLong extends AtomicLong {
        BTraceAtomicLong(long initVal) {
            super(initVal);
        }
    }
}

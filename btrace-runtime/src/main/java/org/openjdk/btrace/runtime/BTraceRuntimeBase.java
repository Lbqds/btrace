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

package org.openjdk.btrace.runtime;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpmcArrayQueue;
import org.jctools.queues.MpscChunkedArrayQueue;
import org.openjdk.btrace.core.ArgsMap;
import org.openjdk.btrace.core.BTraceRuntime;
import org.openjdk.btrace.core.BTraceUtils;
import org.openjdk.btrace.core.DebugSupport;
import org.openjdk.btrace.core.Profiler;
import org.openjdk.btrace.core.comm.Command;
import org.openjdk.btrace.core.comm.CommandListener;
import org.openjdk.btrace.core.comm.ErrorCommand;
import org.openjdk.btrace.core.comm.EventCommand;
import org.openjdk.btrace.core.comm.ExitCommand;
import org.openjdk.btrace.core.comm.MessageCommand;
import org.openjdk.btrace.core.handlers.ErrorHandler;
import org.openjdk.btrace.core.handlers.EventHandler;
import org.openjdk.btrace.core.handlers.ExitHandler;
import org.openjdk.btrace.core.handlers.LowMemoryHandler;
import org.openjdk.btrace.core.handlers.TimerHandler;
import org.openjdk.btrace.runtime.profiling.MethodInvocationProfiler;
import org.openjdk.btrace.services.api.RuntimeContext;
import sun.misc.Unsafe;

import javax.management.ListenerNotFoundException;
import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Base class form multiple Java version specific implementation.
 *
 * Helper class used by BTrace built-in functions and
 * also acts runtime "manager" for a specific BTrace client
 * and sends Commands to the CommandListener passed.
 *
 * @author A. Sundararajan
 * @author Christian Glencross (aggregation support)
 * @author Joachim Skeie (GC MBean support, advanced Deque manipulation)
 * @author KLynch
 */
public abstract class BTraceRuntimeBase implements BTraceRuntime.IBTraceRuntime, RuntimeContext {
    private static final String HOTSPOT_BEAN_NAME =
            "com.sun.management:type=HotSpotDiagnostic";

    static final class RTWrapper {
        private BTraceRuntimeImpl rt = null;

        boolean set(BTraceRuntimeImpl other) {
            if (rt != null && other != null) {
                return false;
            }
            rt = other;
            return true;
        }

        void escape(Callable<Void> c) {
            BTraceRuntimeImpl oldRuntime = rt;
            rt = null;
            try {
                c.call();
            } catch (Exception ignored) {
            } finally {
                if (oldRuntime != null) {
                    rt = oldRuntime;
                }
            }
        }
    }

    private static final class Accessor implements BTraceRuntime.BTraceRuntimeAccessor {
        @Override
        public BTraceRuntime.IBTraceRuntime getRt() {
            BTraceRuntimeBase current = getCurrent();
            return current != null ? current : dummy;
        }

    }

    private static final class ConsumerWrapper implements MessagePassingQueue.Consumer<Command> {
        private final CommandListener cmdHandler;
        private final AtomicBoolean exitSignal;

        public ConsumerWrapper(CommandListener cmdHandler, AtomicBoolean exitSignal) {
            this.cmdHandler = cmdHandler;
            this.exitSignal = exitSignal;
        }

        @Override
        public void accept(Command t) {
            try {
                cmdHandler.onCommand(t);
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }
            if (t.getType() == Command.EXIT) {
                exitSignal.set(true);
            }
        }
    }
    // to be registered by BTraceRuntimeImpl implementation class
    // should be treated as virtually immutable
    static volatile BTraceRuntimeImpl dummy = null;

    // we need Unsafe to load BTrace class bytes as
    // bootstrap class
    private static final Unsafe unsafe;

    private static Properties dotWriterProps;

    // are we running with DTrace support enabled?
    private static boolean dtraceEnabled;

    private static final boolean messageTimestamp = false;

    // the command FIFO queue related settings
    private static final int CMD_QUEUE_LIMIT_DEFAULT = 100;

    // the command FIFO queue upper limit
    private static int CMD_QUEUE_LIMIT;
    protected static final ThreadLocal<RTWrapper> rt;

    static {
        rt = new ThreadLocal<RTWrapper>() {
            @Override
            protected RTWrapper initialValue() {
                return new RTWrapper();
            }
        };
        registerBTraceRuntime();
        unsafe = BTraceRuntime.initUnsafe();
        setupCmdQueueParams();
        // ignore
    }

    // for testing purposes
    private static volatile boolean uniqueClientClassNames = true;

    // BTraceRuntime against BTrace class name
    protected static final Map<String, BTraceRuntimeImpl> runtimes = new ConcurrentHashMap<>();

    // a set of all the client names connected so far
    private static final Set<String> clients = new HashSet<>();

    // jvmstat related stuff
    // interface to read perf counters of this process
    protected static volatile PerfReader perfReader;
    // performance counters created by this client
    protected static final Map<String, ByteBuffer> counters = new HashMap<>();

    // Few MBeans used to implement certain built-in functions
    private static volatile MemoryMXBean memoryMBean;
    private static volatile List<MemoryPoolMXBean> memPoolList;
    private static volatile HotSpotDiagnosticMXBean hotspotMBean;
    private static volatile RuntimeMXBean runtimeMBean;
    private static volatile ThreadMXBean threadMBean;
    private static volatile List<GarbageCollectorMXBean> gcBeanList;
    private static volatile OperatingSystemMXBean operatingSystemMXBean;

    // Per-client state starts here.

    private final DebugSupport debug;

    // current thread's exception
    private final ThreadLocal<Throwable> currentException = new ThreadLocal<>();

    // "command line" args supplied by client
    private final ArgsMap args;

    // whether current runtime has been disabled?
    protected volatile boolean disabled;

    // Class object of the BTrace class [of this client]
    private final String className;

    // BTrace Class object corresponding to this client
    private Class clazz;

    // instrumentation level field for each runtime
    private Field level;

    // array of timer callback methods
    private TimerHandler[] timerHandlers;
    private EventHandler[] eventHandlers;
    private ErrorHandler[] errorHandlers;
    private ExitHandler[] exitHandlers;
    private LowMemoryHandler[] lowMemoryHandlers;

    // map of client event handling methods
    private Map<String, Method> eventHandlerMap;
    private Map<String, LowMemoryHandler> lowMemoryHandlerMap;

    // timer to run profile provider actions
    private volatile Timer timer;

    // executer to run low memory handlers
    private volatile ExecutorService threadPool;
    // Memory MBean listener
    private volatile NotificationListener memoryListener;

    // Command queue for the client
    private final MpscChunkedArrayQueue<Command> queue;

    private static class SpeculativeQueueManager {
        // maximum number of speculative buffers
        private static final int MAX_SPECULATIVE_BUFFERS = Short.MAX_VALUE;
        // per buffer message limit
        private static final int MAX_SPECULATIVE_MSG_LIMIT = Short.MAX_VALUE;
        // next speculative buffer id
        private int nextSpeculationId;
        // speculative buffers map
        private ConcurrentHashMap<Integer, MpmcArrayQueue<Command>> speculativeQueues;
        // per thread current speculative buffer id
        private ThreadLocal<Integer> currentSpeculationId;

        SpeculativeQueueManager() {
            speculativeQueues = new ConcurrentHashMap<>();
            currentSpeculationId = new ThreadLocal<>();
        }

        void clear() {
            speculativeQueues.clear();
            speculativeQueues = null;
            currentSpeculationId.remove();
            currentSpeculationId = null;
        }

        int speculation() {
            int nextId = getNextSpeculationId();
            if (nextId != -1) {
                speculativeQueues.put(nextId,
                        new MpmcArrayQueue<Command>(MAX_SPECULATIVE_MSG_LIMIT));
            }
            return nextId;
        }

        boolean send(Command cmd) {
            if (currentSpeculationId != null){
                Integer curId = currentSpeculationId.get();
                if ((curId != null) && (cmd.getType() != Command.EXIT)) {
                    MpmcArrayQueue<Command> sb = speculativeQueues.get(curId);
                    if (sb != null) {
                        if (!sb.offer(cmd)) {
                            sb.clear();
                            sb.offer(new MessageCommand("speculative buffer overflow: " + curId));
                        }
                        return true;
                    }
                }
            }
            return false;
        }

        void speculate(int id) {
            validateId(id);
            currentSpeculationId.set(id);
        }

        void commit(int id, MpscChunkedArrayQueue<Command> result) {
            validateId(id);
            currentSpeculationId.set(null);
            MpmcArrayQueue<Command> sb = speculativeQueues.get(id);
            if (sb != null) {
                result.addAll(sb);
                sb.clear();
            }
        }

        void discard(int id) {
            validateId(id);
            currentSpeculationId.set(null);
            speculativeQueues.get(id).clear();
        }

        // -- Internals only below this point
        private synchronized int getNextSpeculationId() {
            if (nextSpeculationId == MAX_SPECULATIVE_BUFFERS) {
                return -1;
            }
            return nextSpeculationId++;
        }

        private void validateId(int id) {
            if (! speculativeQueues.containsKey(id)) {
                throw new RuntimeException("invalid speculative buffer id: " + id);
            }
        }
    }
    // per client speculative buffer manager
    private final SpeculativeQueueManager specQueueManager;
    // background thread that sends Commands to the handler
    private volatile Thread cmdThread;
    private final Instrumentation instrumentation;

    private final AtomicBoolean exitting = new AtomicBoolean(false);
    private final MessagePassingQueue.WaitStrategy waitStrategy = new MessagePassingQueue.WaitStrategy() {
        @Override
        public int idle(int i) {
            if (exitting.get()) return 0;
            try {
                if (i < 3000) {
                    Thread.yield();
                } else if (i < 3100) {
                    Thread.sleep(1);
                } else {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                return 0;
            }
            return i+1;
        }
    };
    private final MessagePassingQueue.ExitCondition exitCondition = new MessagePassingQueue.ExitCondition() {
        @Override
        public boolean keepRunning() {
            return !exitting.get();
        }
    };

    BTraceRuntimeBase() {
        debug = new DebugSupport(null);
        args = null;
        queue = null;
        specQueueManager = null;
        className = null;
        instrumentation = null;
    }

    BTraceRuntimeBase(final String className, ArgsMap args,
                             final CommandListener cmdListener,
                             DebugSupport ds, Instrumentation inst) {
        this.args = args;
        queue = new MpscChunkedArrayQueue<>(CMD_QUEUE_LIMIT_DEFAULT);
        specQueueManager = new SpeculativeQueueManager();
        this.className = className;
        instrumentation = inst;
        debug = ds != null ? ds : new DebugSupport(null);

        addRuntime(className);
        cmdThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    enter();
                    queue.drain(
                            new ConsumerWrapper(cmdListener, exitting),
                            waitStrategy, exitCondition
                    );
                } finally {
                    runtimes.remove(className);
                    queue.clear();
                    specQueueManager.clear();
                    leave();
                    disabled = true;
                }
            }
        });
        cmdThread.setDaemon(true);
        cmdThread.start();
    }

    protected abstract void addRuntime(String className);

    @Override
    public int getInstrumentationLevel() {
        BTraceRuntimeBase cur = getCurrent();

        try {
            return cur.level.getInt(cur);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void setInstrumentationLevel(int level) {
        BTraceRuntimeBase cur = getCurrent();
        try {
            cur.level.set(cur, level);
        } catch (Exception e) {
            // ignore
        }
    }

    public static String getClientName(String forClassName) {
        if (!uniqueClientClassNames) {
            return forClassName;
        }

        String name = forClassName;
        int suffix = 1;
        while (clients.contains(name)) {
            name = forClassName + "$" + (suffix++);
        }
        clients.add(name);
        return name;
    }

//    The following methods are be implemented in Java version specific way in version specific classes
//    ==================================================================================================
//    @CallerSensitive
//    public static void init(PerfReader perfRead) {
//        BTraceRuntime.initUnsafe();
//
//        Class caller = isNewerThan8 ? Reflection.getCallerClass() : Reflection.getCallerClass(2);
//        if (! caller.getName().equals("org.openjdk.btrace.agent.Client")) {
//            throw new SecurityException("unsafe init");
//        }
//        perfReader = perfRead;
//        loadLibrary(perfRead.getClass().getClassLoader());
//    }
//
//    @CallerSensitive
//    public Class defineClass(byte[] code) {
//        Class caller = isNewerThan8 ? Reflection.getCallerClass() : Reflection.getCallerClass(2);
//        if (! caller.getName().startsWith("org.openjdk.btrace.")) {
//            throw new SecurityException("unsafe defineClass");
//        }
//        return defineClassImpl(code, true);
//    }
//
//    @CallerSensitive
//    public Class defineClass(byte[] code, boolean mustBeBootstrap) {
//        Class caller = isNewerThan8 ? Reflection.getCallerClass() : Reflection.getCallerClass(2);
//        if (! caller.getName().startsWith("org.openjdk.btrace.")) {
//            throw new SecurityException("unsafe defineClass");
//        }
//        return defineClassImpl(code, mustBeBootstrap);
//    }
//    /**
//     * Utility to create a new jvmstat perf counter. Called
//     * by preprocessed BTrace class to create perf counter
//     * for each @Export variable.
//     */
//    public static void newPerfCounter(String name, String desc, Object value) {
//        newPerfCounter(value, name, desc);
//    }
//
//    public static void newPerfCounter(Object value, String name, String desc) {
//        BTPerf perf = getPerf();
//        char tc = desc.charAt(0);
//        switch (tc) {
//            case 'C':
//            case 'Z':
//            case 'B':
//            case 'S':
//            case 'I':
//            case 'J':
//            case 'F':
//            case 'D': {
//                long initValue = (value != null)? ((Number)value).longValue() : 0L;
//                ByteBuffer b = perf.createLong(name, V_Variable, V_None, initValue);
//                b.order(ByteOrder.nativeOrder());
//                counters.put(name, b);
//            }
//            break;
//
//            case '[':
//                break;
//            case 'L': {
//                if (desc.equals("Ljava/lang/String;")) {
//                    byte[] buf;
//                    if (value != null) {
//                        buf = getStringBytes((String)value);
//                    } else {
//                        buf = new byte[PERF_STRING_LIMIT];
//                        buf[0] = '\0';
//                    }
//                    ByteBuffer b = perf.createByteArray(name, V_Variable, V_String,
//                            buf, buf.length);
//                    counters.put(name, b);
//                }
//            }
//            break;
//        }
//    }
//
//    /**
//     * Return the value of integer perf. counter of given name.
//     */
//    public static int getPerfInt(String name) {
//        return (int) getPerfLong(name);
//    }
//
//    /**
//     * Write the value of integer perf. counter of given name.
//     */
//    public static void putPerfInt(int value, String name) {
//        long l = value;
//        putPerfLong(l, name);
//    }
//
//    /**
//     * Return the value of float perf. counter of given name.
//     */
//    public static float getPerfFloat(String name) {
//        int val = getPerfInt(name);
//        return Float.intBitsToFloat(val);
//    }
//
//    /**
//     * Write the value of float perf. counter of given name.
//     */
//    public static void putPerfFloat(float value, String name) {
//        int i = Float.floatToRawIntBits(value);
//        putPerfInt(i, name);
//    }
//
//    /**
//     * Return the value of long perf. counter of given name.
//     */
//    public static long getPerfLong(String name) {
//        ByteBuffer b = counters.get(name);
//        synchronized(b) {
//            long l = b.getLong();
//            b.rewind();
//            return l;
//        }
//    }
//
//    /**
//     * Write the value of float perf. counter of given name.
//     */
//    public static void putPerfLong(long value, String name) {
//        ByteBuffer b = counters.get(name);
//        synchronized (b) {
//            b.putLong(value);
//            b.rewind();
//        }
//    }
//
//    /**
//     * Return the value of double perf. counter of given name.
//     */
//    public static double getPerfDouble(String name) {
//        long val = getPerfLong(name);
//        return Double.longBitsToDouble(val);
//    }
//
//    /**
//     * write the value of double perf. counter of given name.
//     */
//    public static void putPerfDouble(double value, String name) {
//        long l = Double.doubleToRawLongBits(value);
//        putPerfLong(l, name);
//    }
//
//    /**
//     * Return the value of String perf. counter of given name.
//     */
//    public static String getPerfString(String name) {
//        ByteBuffer b = counters.get(name);
//        byte[] buf = new byte[b.limit()];
//        byte t = (byte)0;
//        int i = 0;
//        synchronized (b) {
//            while ((t = b.get()) != '\0') {
//                buf[i++] = t;
//            }
//            b.rewind();
//        }
//        return new String(buf, 0, i, StandardCharsets.UTF_8);
//    }
//
//    /**
//     * Write the value of float perf. counter of given name.
//     */
//    public static void putPerfString(String value, String name) {
//        ByteBuffer b = counters.get(name);
//        byte[] v = getStringBytes(value);
//        synchronized (b) {
//            b.put(v);
//            b.rewind();
//        }
//    }
//    private static Perf getPerf() {
//        if (perf == null) {
//            synchronized(BTraceRuntime.class) {
//                if (perf == null) {
//                    perf = AccessController.doPrivileged(new Perf.GetPerfAction());
//                }
//            }
//        }
//        return perf;
//    }
//    /**
//     * Enter method is called by every probed method just
//     * before the probe actions start.
//     */
//    public static boolean enter(BTraceRuntimeImpl current) {
//        if (current.disabled) return false;
//        return rt.get().set(current);
//    }
//    ==================================================================================================

    public void shutdownCmdLine() {
        exitting.set(true);
    }

    /**
     * Leave method is called by every probed method just
     * before the probe actions end (and actual probed
     * method continues).
     */
    @Override
    public void leave() {
        rt.get().set(null);
    }

    /**
     * start method is called by every BTrace (preprocesed) class
     * just at the end of it's class initializer.
     */
    public void start() {
        if (timerHandlers != null) {
            timer = new Timer(true);
            TimerTask[] timerTasks = new TimerTask[timerHandlers.length];
            wrapToTimerTasks(timerTasks);
            for (int index = 0; index < timerHandlers.length; index++) {
                TimerHandler th = timerHandlers[index];
                long period = th.period;
                String periodArg = th.periodArg;
                if (periodArg != null) {
                    period = BTraceRuntime.parseLong(args.template(periodArg), period);
                }
                timer.schedule(timerTasks[index], period, period);
            }
        }

        if (lowMemoryHandlers != null) {
            initMBeans();
            lowMemoryHandlerMap = new HashMap<>();
            for (LowMemoryHandler lmh : lowMemoryHandlers) {
                String poolName = args.template(lmh.pool);
                lowMemoryHandlerMap.put(poolName, lmh);
            }
            for (MemoryPoolMXBean mpoolBean : memPoolList) {
                String name = mpoolBean.getName();
                LowMemoryHandler lmh = lowMemoryHandlerMap.get(name);
                if (lmh != null) {
                    if (mpoolBean.isUsageThresholdSupported()) {
                        mpoolBean.setUsageThreshold(lmh.threshold);
                    }
                }
            }
            NotificationEmitter emitter = (NotificationEmitter) memoryMBean;
            emitter.addNotificationListener(memoryListener, null, null);
        }

        leave();
    }

    public void handleExit(int exitCode) {
        exitImpl(exitCode);
        try {
            cmdThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void handleEvent(EventCommand ecmd) {
        if (eventHandlers != null) {
            if (eventHandlerMap == null) {
                eventHandlerMap = new HashMap<>();
                for (EventHandler eh : eventHandlers) {
                    try {
                        String eventName = args.template(eh.getEvent());
                        eventHandlerMap.put(eventName, eh.getMethod(clazz));
                    } catch (NoSuchMethodException e) {}
                }
            }
            String event = ecmd.getEvent();
            event = event != null ? event : EventHandler.ALL_EVENTS;

            final Method eventHandler = eventHandlerMap.get(event);
            if (eventHandler != null) {
                rt.get().escape(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        eventHandler.invoke(null, (Object[])null);
                        return null;
                    }
                });
            }
        }
    }

    /**
     * One instance of BTraceRuntime is created per-client.
     * This forClass method creates it. Class passed is the
     * preprocessed BTrace program of the client.
     */
    public static BTraceRuntimeBase forClass(Class cl, TimerHandler[] tHandlers, EventHandler[] evHandlers, ErrorHandler[] errHandlers,
                                             ExitHandler[] eHandlers, LowMemoryHandler[] lmHandlers) {
        BTraceRuntimeBase runtime = runtimes.get(cl.getName());
        runtime.init(cl, tHandlers, evHandlers, errHandlers, eHandlers, lmHandlers);
        return runtime;
    }

    /**
     * Utility to create a new ThreadLocal object. Called
     * by preprocessed BTrace class to create ThreadLocal
     * for each @TLS variable.
     * @param initValue Initial value.
     *                  This value must be either a boxed primitive or {@linkplain Cloneable}.
     *                  In case a {@linkplain Cloneable} value is provided the value is never used directly
     *                  - instead, a new clone of the value is created per thread.
     */
    public static ThreadLocal newThreadLocal(final Object initValue) {
        return new ThreadLocal() {
            @Override
            protected Object initialValue() {
                if (initValue == null) return initValue;

                if (initValue instanceof Cloneable) {
                    try {
                        Class clz = initValue.getClass();
                        Method m = clz.getDeclaredMethod("clone");
                        m.setAccessible(true);
                        return m.invoke(initValue);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                }
                return initValue;
            }
        };
    }

    // The following constants are copied from VM code
    // for jvmstat.

    // perf counter variability - we always variable variability
    private static final int V_Variable = 3;
    // perf counter units
    private static final int V_None = 1;
    private static final int V_String = 5;
    private static final int PERF_STRING_LIMIT = 256;

    /**
     * Handles exception from BTrace probe actions.
     */
    @Override
    public void handleException(Throwable th) {
        BTraceRuntimeBase current = getCurrent();
        if (current != null) {
            current.handleExceptionImpl(th);
        } else {
            th.printStackTrace();
        }
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

    private static String identityStr(Object obj) {
        int hashCode = System.identityHashCode(obj);
        return obj.getClass().getName() + "@" + Integer.toHexString(hashCode);
    }

    // package-private interface to BTraceUtils class.

    @Override
    public int speculation() {
        BTraceRuntimeBase current = getCurrent();
        return current.specQueueManager.speculation();
    }

    @Override
    public void speculate(int id) {
        BTraceRuntimeBase current = getCurrent();
        current.specQueueManager.speculate(id);
    }

    @Override
    public void discard(int id) {
        BTraceRuntimeBase current = getCurrent();
        current.specQueueManager.discard(id);
    }

    @Override
    public void commit(int id) {
        BTraceRuntimeBase current = getCurrent();
        current.specQueueManager.commit(id, current.queue);
    }

    public static void retransform(String runtimeName, Class<?> clazz) {
        try {
            BTraceRuntimeBase rt = runtimes.get(runtimeName);
            if (rt != null && rt.instrumentation.isModifiableClass(clazz)) {
                rt.instrumentation.retransformClasses(clazz);
            }
        } catch (Throwable e) {
            warning(e);
        }
    }

    @Override
    public long sizeof(Object obj) {
        BTraceRuntimeBase runtime = getCurrent();
        return runtime.instrumentation.getObjectSize(obj);
    }

    // BTrace command line argument functions
    @Override
    public int $length() {
        BTraceRuntimeBase runtime = getCurrent();
        return runtime.args == null? 0 : runtime.args.size();
    }

    @Override
    public String $(int n) {
        BTraceRuntimeBase runtime = getCurrent();
        if (runtime.args == null) {
            return null;
        } else {
            return runtime.args.get(n);
        }
    }

    @Override
    public String $(String key) {
        BTraceRuntimeBase runtime = getCurrent();
        if (runtime.args == null) {
            return null;
        } else {
            return runtime.args.get(key);
        }
    }

    // BTrace perf counter reading functions
    @Override
    public int perfInt(String name) {
        return getPerfReader().perfInt(name);
    }

    @Override
    public long perfLong(String name) {
        return getPerfReader().perfLong(name);
    }

    @Override
    public String perfString(String name) {
        return getPerfReader().perfString(name);
    }

    @Override
    public String toXML(Object obj) {
        try {
            return XMLSerializer.toXML(obj);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }

    @Override
    public void writeXML(Object obj, String fileName) {
        try {
            Path p = FileSystems.getDefault().getPath(resolveFileName(fileName));
            try (BufferedWriter bw = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                XMLSerializer.write(obj, bw);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception exp) {
            throw new RuntimeException(exp);
        }
    }

    private static synchronized void initDOTWriterProps() {
        if (dotWriterProps == null) {
            dotWriterProps = new Properties();
            InputStream is = BTraceRuntime.class.getResourceAsStream("resources/btrace.dotwriter.properties");
            if (is != null) {
                try {
                    dotWriterProps.load(is);
                } catch (IOException ioExp) {
                    ioExp.printStackTrace();
                }
            }
            try {
                String home = System.getProperty("user.home");
                File file = new File(home, "btrace.dotwriter.properties");
                if (file.exists() && file.isFile()) {
                    is = new BufferedInputStream(new FileInputStream(file));
                    dotWriterProps.load(is);
                }
            } catch (Exception exp) {
                exp.printStackTrace();
            }
        }
    }

    @Override
    public void writeDOT(Object obj, String fileName) {
        DOTWriter writer = new DOTWriter(resolveFileName(fileName));
        initDOTWriterProps();
        writer.customize(dotWriterProps);
        writer.addNode(null, obj);
        writer.close();
    }

    // profiling related methods
    /**
     * @see BTraceUtils.Profiling#newProfiler()
     */
    @Override
    public Profiler newProfiler() {
        return new MethodInvocationProfiler(600);
    }

    /**
     * @see BTraceUtils.Profiling#newProfiler(int)
     */
    @Override
    public Profiler newProfiler(int expectedMethodCnt) {
        return new MethodInvocationProfiler(expectedMethodCnt);
    }

    @Override
    public RuntimeMXBean getRuntimeMXBean() {
        initRuntimeMBean();
        return runtimeMBean;
    }

    @Override
    public ThreadMXBean getThreadMXBean() {
        initThreadMBean();
        return threadMBean;
    }

    @Override
    public OperatingSystemMXBean getOperatingSystemMXBean() {
        initOperatingSystemMBean();
        return operatingSystemMXBean;
    }

    @Override
    public List<GarbageCollectorMXBean> getGCMBeans() {
        initGcMBeans();
        return gcBeanList;
    }

    @Override
    public HotSpotDiagnosticMXBean getHotspotMBean() {
        initHotspotMBean();
        return hotspotMBean;
    }

    /**
     * Get the current thread BTraceRuntime instance
     * if there is one.
     */
    private static BTraceRuntimeImpl getCurrent() {
        RTWrapper rtw = rt.get();
        BTraceRuntimeImpl current = rtw != null ? rtw.rt : null;
        current = current != null ? current : (BTraceRuntimeImpl)dummy;
        assert current != null : "BTraceRuntime is null!";
        return current;
    }

    private void initThreadPool() {
        if (threadPool == null) {
            synchronized (this) {
                if (threadPool == null) {
                    threadPool = Executors.newFixedThreadPool(1,
                            new ThreadFactory() {
                                @Override
                                public Thread newThread(Runnable r) {
                                    Thread th = new Thread(r, "BTrace Worker");
                                    th.setDaemon(true);
                                    return th;
                                }
                            });
                }
            }
        }
    }

    private void initMBeans() {
        initMemoryMBean();
        initOperatingSystemMBean();
        initRuntimeMBean();
        initThreadMBean();
        initHotspotMBean();
        initGcMBeans();
        initMemoryPoolList();
        initMemoryListener();
    }

    private void initMemoryListener() {
        initThreadPool();
        memoryListener = new NotificationListener() {
            @Override
            @SuppressWarnings("FutureReturnValueIgnored")
            public void handleNotification(Notification notif, Object handback)  {
                boolean entered = enter();
                try {
                    String notifType = notif.getType();
                    if (notifType.equals(MemoryNotificationInfo.MEMORY_THRESHOLD_EXCEEDED)) {
                        CompositeData cd = (CompositeData) notif.getUserData();
                        final MemoryNotificationInfo info = MemoryNotificationInfo.from(cd);
                        String name = info.getPoolName();
                        final LowMemoryHandler handler = lowMemoryHandlerMap.get(name);
                        if (handler != null) {
                            threadPool.submit(new Runnable() {
                                @Override
                                public void run() {
                                    boolean entered = enter();
                                    try {
                                        if (handler.trackUsage) {
                                            handler.invoke(clazz, null, info.getUsage());
                                        } else {
                                            handler.invoke(clazz, null, null);
                                        }
                                    } catch (Throwable th) {
                                    } finally {
                                        if (entered) {
                                            BTraceRuntime.leave();
                                        }
                                    }
                                }
                            });
                        }
                    }
                } finally {
                    if (entered) {
                        BTraceRuntime.leave();
                    }
                }
            }
        };
    }

    private static PerfReader getPerfReader() {
        if (perfReader == null) {
            throw new UnsupportedOperationException();
        }
        return perfReader;
    }

    @Override
    public void send(String msg) {
        send(new MessageCommand(messageTimestamp? System.nanoTime() : 0L,
                msg));
    }

    @Override
    public void send(Command cmd) {
        boolean speculated = specQueueManager.send(cmd);
        if (! speculated) {
            enqueue(cmd);
        }
    }

    private void enqueue(Command cmd) {
        int backoffCntr = 0;
        while (queue != null && !queue.relaxedOffer(cmd)) {
            try {
                if (backoffCntr < 3000) {
                    Thread.yield();
                } else if (backoffCntr < 3100) {
                    Thread.sleep(1);
                } else {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {}
            backoffCntr++;
        }
    }

    private void handleExceptionImpl(Throwable th) {
        if (currentException.get() != null) {
            return;
        }
        boolean entered = enter();
        try {
            currentException.set(th);

            if (th instanceof ExitException) {
                exitImpl(((ExitException)th).exitCode());
            } else {
                if (errorHandlers != null) {
                    for (ErrorHandler eh : errorHandlers) {
                        try {
                            eh.getMethod(clazz).invoke(null, th);
                        } catch (Throwable ignored) {
                        }
                    }
                } else {
                    // Do not call send(Command). Exception messages should not
                    // go to speculative buffers!
                    enqueue(new ErrorCommand(th));
                }
            }
        } finally {
            currentException.set(null);
            if (entered) {
                leave();
            }
        }
    }

    private void wrapToTimerTasks(TimerTask[] tasks) {
        for (int index = 0; index < timerHandlers.length; index++) {
            final TimerHandler th = timerHandlers[index];
            tasks[index] = new TimerTask() {
                final Method mthd;
                {
                    Method m = null;
                    try {
                        m = th.getMethod(clazz);
                    } catch (NoSuchMethodException e) {
                        e.printStackTrace();
                    }
                    mthd = m;
                }
                @Override
                public void run() {
                    if (mthd != null) {
                        try {
                            mthd.invoke(null, (Object[])null);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
        }
    }

    @Override
    public void exit(int exitCode) {
        exitImpl(exitCode);
    }

    private synchronized void exitImpl(int exitCode) {
        boolean entered = enter();
        try {
            if (timer != null) {
                timer.cancel();
            }

            if (memoryListener != null && memoryMBean != null) {
                NotificationEmitter emitter = (NotificationEmitter) memoryMBean;
                try {
                    emitter.removeNotificationListener(memoryListener);
                } catch (ListenerNotFoundException lnfe) {}
            }

            if (threadPool != null) {
                threadPool.shutdownNow();
            }

            if (exitHandlers != null) {
                for (ExitHandler eh : exitHandlers) {
                    try {
                        eh.getMethod(clazz).invoke(null, exitCode);
                    } catch (Throwable ignored) {}
                }
                exitHandlers = null;
            }

            send(new ExitCommand(exitCode));
        } finally {
            disabled = true;
            if (entered) {
                BTraceRuntime.leave();
            }
        }
    }

    protected static byte[] getStringBytes(String value) {
        byte[] v = null;
        v = value.getBytes(StandardCharsets.UTF_8);
        byte[] v1 = new byte[v.length+1];
        System.arraycopy(v, 0, v1, 0, v.length);
        v1[v.length] = '\0';
        return v1;
    }

    protected final Class defineClassImpl(byte[] code, boolean mustBeBootstrap) {
        ClassLoader loader = null;
        if (! mustBeBootstrap) {
            loader = new ClassLoader(null) {};
        }
        Class cl = unsafe.defineClass(className, code, 0, code.length, loader, null);
        unsafe.ensureClassInitialized(cl);
        return cl;
    }

    private void init(Class cl, TimerHandler[] tHandlers, EventHandler[] evHandlers, ErrorHandler[] errHandlers,
                      ExitHandler[] eHandlers, LowMemoryHandler[] lmHandlers) {
        debugPrint("init: clazz = " + clazz + ", cl = " + cl);
        if (clazz != null) {
            return;
        }

        clazz = cl;

        debugPrint("init: timerHandlers = " + Arrays.deepToString(tHandlers));
        timerHandlers = tHandlers;
        eventHandlers = evHandlers;
        errorHandlers = errHandlers;
        exitHandlers = eHandlers;
        lowMemoryHandlers = lmHandlers;

        try {
            level = cl.getDeclaredField("$btrace$$level");
            level.setAccessible(true);
            int levelVal = BTraceRuntime.parseInt(args.get("level"), Integer.MIN_VALUE);
            if (levelVal > Integer.MIN_VALUE) {
                level.set(null, levelVal);
            }
        } catch (Throwable e) {
            debugPrint("Instrumentation level setting not available");
        }

        BTraceMBean.registerMBean(clazz);
    }

    @Override
    public String resolveFileName(String name) {
        if (name.indexOf(File.separatorChar) != -1) {
            throw new IllegalArgumentException("directories are not allowed");
        }
        StringBuilder buf = new StringBuilder();
        buf.append('.');
        buf.append(File.separatorChar);
        BTraceRuntimeBase runtime = getCurrent();
        buf.append("btrace");
        if (runtime.args != null && runtime.args.size() > 0) {
            buf.append(runtime.args.get(0));
        }
        buf.append(File.separatorChar);
        buf.append(runtime.className);
        new File(buf.toString()).mkdirs();
        buf.append(File.separatorChar);
        buf.append(name);
        return buf.toString();
    }

    protected static void loadLibrary(final ClassLoader cl) {
        AccessController.doPrivileged(new PrivilegedAction() {
            @Override
            public Object run() {
                loadBTraceLibrary(cl);
                return null;
            }
        });
    }

    private static void loadBTraceLibrary(ClassLoader loader) {
        boolean isSolaris = System.getProperty("os.name").equals("SunOS");
        if (isSolaris) {
            try {
                System.loadLibrary("btrace");
                dtraceEnabled = true;
            } catch (LinkageError le) {
                URL btracePkg = null;
                if (loader != null) {
                    btracePkg = loader.getResource("org/openjdk/btrace");
                }

                if (btracePkg == null) {
                    warning("cannot load libbtrace.so, will miss DTrace probes from BTrace");
                    return;
                }

                String path = btracePkg.toString();
                int archSeparator = path.indexOf('!');
                if (archSeparator != -1) {
                    path = path.substring(0, archSeparator);
                    path = path.substring("jar:".length(), path.lastIndexOf('/'));
                } else {
                    int buildSeparator = path.indexOf("/classes/");
                    if (buildSeparator != -1) {
                        path = path.substring(0, buildSeparator);
                    }
                }
                String cpu = System.getProperty("os.arch");
                if (cpu.equals("x86")) {
                    cpu = "i386";
                }
                path += "/" + cpu + "/libbtrace.so";
                try {
                    path = new File(new URI(path)).getAbsolutePath();
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                try {
                    System.load(path);
                    dtraceEnabled = true;
                } catch (LinkageError le1) {
                    warning("cannot load libbtrace.so, will miss DTrace probes from BTrace");
                }
            }
        }
    }

    private static void setupCmdQueueParams() {
        String maxQLen = System.getProperty(BTraceRuntime.CMD_QUEUE_LIMIT_KEY, null);
        if (maxQLen == null) {
            CMD_QUEUE_LIMIT = CMD_QUEUE_LIMIT_DEFAULT;
            debugPrint0("\"" + BTraceRuntime.CMD_QUEUE_LIMIT_KEY + "\" not provided. " +
                    "Using the default cmd queue limit of " + CMD_QUEUE_LIMIT_DEFAULT);
        } else {
            try {
                CMD_QUEUE_LIMIT = Integer.parseInt(maxQLen);
                debugPrint0("The cmd queue limit set to " + CMD_QUEUE_LIMIT);
            } catch (NumberFormatException e) {
                warning("\"" + maxQLen + "\" is not a valid int number. " +
                        "Using the default cmd queue limit of " + CMD_QUEUE_LIMIT_DEFAULT);
                CMD_QUEUE_LIMIT = CMD_QUEUE_LIMIT_DEFAULT;
            }
        }
    }

    public static void registerBTraceRuntime() {
        try {
            Field fld = BTraceRuntime.class.getDeclaredField("rtAccessor");
            fld.setAccessible(true);
            fld.set(null, new Accessor());
        } catch (IllegalAccessException | IllegalArgumentException | NoSuchFieldException | SecurityException e) {
            DebugSupport.warning(e);
        }
    }

    private static void debugPrint0(String msg) {
        getCurrent().debugPrint(msg);
    }

    private static void debugPrint0(Throwable t) {
        getCurrent().debugPrint(t);
    }

    protected void debugPrint(String msg) {
        debug.debug(msg);
    }

    @Override
    public void debugPrint(Throwable t) {
        debug.debug(t);
    }

    private static void warning(String msg) {
        DebugSupport.warning(msg);
    }

    private static void warning(Throwable t) {
        DebugSupport.warning(t);
    }

    private static void initMemoryPoolList() {
        synchronized (BTraceRuntimeBase.class) {
            if (memPoolList == null) {
                try {
                    memPoolList = AccessController.doPrivileged(
                            new PrivilegedExceptionAction<List<MemoryPoolMXBean>>() {
                                @Override
                                public List<MemoryPoolMXBean> run() throws Exception {
                                    return ManagementFactory.getMemoryPoolMXBeans();
                                }
                            });
                } catch (Exception exp) {
                    throw new UnsupportedOperationException(exp);
                }
            }
        }
    }

    private static void initMemoryMBean() {
        synchronized (BTraceRuntimeBase.class) {
            if (memoryMBean == null) {
                try {
                    memoryMBean = AccessController.doPrivileged(
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
        }
    }

    private static void initOperatingSystemMBean() {
        synchronized (BTraceRuntimeBase.class) {
            if (operatingSystemMXBean == null) {
                try {
                    operatingSystemMXBean = AccessController.doPrivileged(
                            new PrivilegedExceptionAction<OperatingSystemMXBean>() {
                                @Override
                                public OperatingSystemMXBean run() throws Exception {
                                    return ManagementFactory.getOperatingSystemMXBean();
                                }
                            }
                    );
                } catch (Exception e) {
                    throw new UnsupportedOperationException(e);
                }
            }
        }
    }

    private static void initRuntimeMBean() {
        synchronized (BTraceRuntimeBase.class) {
            if (runtimeMBean == null) {
                try {
                    runtimeMBean = AccessController.doPrivileged(new PrivilegedExceptionAction<RuntimeMXBean>() {
                        @Override
                        public RuntimeMXBean run() throws Exception {
                            return ManagementFactory.getRuntimeMXBean();
                        }
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void initThreadMBean() {
        synchronized (BTraceRuntimeBase.class) {
            if (threadMBean == null) {
                try {
                    threadMBean = AccessController.doPrivileged(new PrivilegedExceptionAction<ThreadMXBean>() {
                        @Override
                        public ThreadMXBean run() throws Exception {
                            return ManagementFactory.getThreadMXBean();
                        }
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void initGcMBeans() {
        synchronized (BTraceRuntimeBase.class) {
            if (gcBeanList == null) {
                try {
                    gcBeanList = AccessController.doPrivileged(new PrivilegedExceptionAction<List<GarbageCollectorMXBean>>() {
                        @Override
                        public List<GarbageCollectorMXBean> run() throws Exception {
                            return ManagementFactory.getGarbageCollectorMXBeans();
                        }
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void initHotspotMBean() {
        synchronized (BTraceRuntimeBase.class) {
            if (hotspotMBean == null) {
                try {
                    hotspotMBean = AccessController.doPrivileged(new PrivilegedExceptionAction<HotSpotDiagnosticMXBean>() {
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
                } catch (Exception e) {
                    throw new UnsupportedOperationException(e);
                }
            }
        }
    }

    @Override
    public boolean isDTraceEnabled() {
        return dtraceEnabled;
    }

    @Override
    public List<MemoryPoolMXBean> getMemoryPoolMXBeans() {
        return memPoolList;
    }

    @Override
    public MemoryMXBean getMemoryMXBean() {
        return memoryMBean;
    }
}

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

import jdk.internal.reflect.CallerSensitive;
import org.openjdk.btrace.core.ArgsMap;
import org.openjdk.btrace.core.BTraceRuntime;

import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import jdk.internal.reflect.Reflection;
import jdk.internal.perf.Perf;
import org.openjdk.btrace.core.DebugSupport;
import org.openjdk.btrace.core.comm.CommandListener;
import org.openjdk.btrace.services.api.RuntimeContext;

/**
 * Helper class used by BTrace built-in functions and
 * also acts runtime "manager" for a specific BTrace client
 * and sends Commands to the CommandListener passed.
 *
 * @author A. Sundararajan
 * @author Christian Glencross (aggregation support)
 * @author Joachim Skeie (GC MBean support, advanced Deque manipulation)
 * @author KLynch
 */
public final class BTraceRuntimeImpl extends BTraceRuntimeBase {
    // perf counter variability - we always variable variability
    private static final int V_Variable = 3;
    // perf counter units
    private static final int V_None = 1;
    private static final int V_String = 5;
    private static final int PERF_STRING_LIMIT = 256;

    private static Perf perf;

    static {
        BTraceRuntimeBase.dummy = new BTraceRuntimeImpl();
    }

    public BTraceRuntimeImpl() {
    }

    public BTraceRuntimeImpl(String className, ArgsMap args, CommandListener cmdListener, DebugSupport ds, Instrumentation inst) {
        super(className, args, cmdListener, ds, inst);
    }

    @Override
    protected void addRuntime(String className) {
        runtimes.put(className, this);
    }

    /**
     * Enter method is called by every probed method just
     * before the probe actions start.
     */
    public static boolean enter(BTraceRuntimeImpl current) {
        if (current.disabled) return false;
        return rt.get().set(current);
    }

    @Override
    public boolean enter() {
        return enter(this);
    }

    @CallerSensitive
    public static void init(PerfReader perfRead) {
        BTraceRuntime.initUnsafe();

        Class caller = Reflection.getCallerClass();
        if (! caller.getName().equals("org.openjdk.btrace.agent.Client")) {
            throw new SecurityException("unsafe init");
        }
        perfReader = perfRead;
        loadLibrary(perfRead.getClass().getClassLoader());
    }

    @CallerSensitive
    public Class defineClass(byte[] code) {
        Class caller = Reflection.getCallerClass();
        if (! caller.getName().startsWith("org.openjdk.btrace.")) {
            throw new SecurityException("unsafe defineClass");
        }
        return defineClassImpl(code, true);
    }

    @CallerSensitive
    public Class defineClass(byte[] code, boolean mustBeBootstrap) {
        Class caller = Reflection.getCallerClass();
        if (! caller.getName().startsWith("org.openjdk.btrace.")) {
            throw new SecurityException("unsafe defineClass");
        }
        return defineClassImpl(code, mustBeBootstrap);
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
                long initValue = (value != null)? ((Number)value).longValue() : 0L;
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
                        buf = getStringBytes((String)value);
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
        long l = value;
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
        synchronized(b) {
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
        byte t = (byte)0;
        int i = 0;
        synchronized (b) {
            while ((t = b.get()) != '\0') {
                buf[i++] = t;
            }
            b.rewind();
        }
        return new String(buf, 0, i, StandardCharsets.UTF_8);
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

    private static Perf getPerf() {
        synchronized(BTraceRuntimeImpl.class) {
            if (perf == null) {
                perf = AccessController.doPrivileged(new Perf.GetPerfAction());
            }
        }
        return perf;
    }
}
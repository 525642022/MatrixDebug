package com.tencent.matrix.trace.core;

import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

import com.tencent.matrix.trace.config.TraceConfig;
import com.tencent.matrix.trace.listeners.LooperObserver;
import com.tencent.matrix.trace.util.Utils;
import com.tencent.matrix.util.MatrixLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;

/**
 * 主线程监控
 */
public class UIThreadMonitor implements BeatLifecycle, Runnable {

    private static final String TAG = "Matrix.UIThreadMonitor";
    private static final String ADD_CALLBACK = "addCallbackLocked";
    private volatile boolean isAlive = false;
    private long[] dispatchTimeMs = new long[4];
    private final HashSet<LooperObserver> observers = new HashSet<>();
    private volatile long token = 0L;
    private boolean isBelongFrame = false;

    /**
     * Callback type: Input callback.  Runs first.
     *
     * @hide
     */
    public static final int CALLBACK_INPUT = 0;

    /**
     * Callback type: Animation callback.  Runs before traversals.
     *
     * @hide
     */
    public static final int CALLBACK_ANIMATION = 1;

    /**
     * Callback type: Commit callback.  Handles post-draw operations for the frame.
     * Runs after traversal completes.
     *
     * @hide
     */
    public static final int CALLBACK_TRAVERSAL = 2;

    /**
     * never do queue end code
     */
    public static final int DO_QUEUE_END_ERROR = -100;

    private static final int CALLBACK_LAST = CALLBACK_TRAVERSAL;

    private final static UIThreadMonitor sInstance = new UIThreadMonitor();
    private TraceConfig config;
    private Object callbackQueueLock; //Choreographer 里的 mLock锁 对象
    private Object[] callbackQueues; //Choreographer 里的 mCallbackQueues 对象 是个 CallbackQueue数组
    private Method addTraversalQueue; //处于第三位的 处理 traversal 的CallbackQueue 对象
    private Method addInputQueue; //处于第一位的 处理 input 的CallbackQueue 对象
    private Method addAnimationQueue; //处于第二位的 处理 animation 的CallbackQueue 对象
    private Choreographer choreographer;
    private long frameIntervalNanos = 16666666;
    private int[] queueStatus = new int[CALLBACK_LAST + 1];
    private boolean[] callbackExist = new boolean[CALLBACK_LAST + 1]; // ABA
    private long[] queueCost = new long[CALLBACK_LAST + 1];
    private static final int DO_QUEUE_DEFAULT = 0;
    private static final int DO_QUEUE_BEGIN = 1;
    private static final int DO_QUEUE_END = 2;
    private boolean isInit = false;

    public static UIThreadMonitor getMonitor() {
        return sInstance;
    }

    public boolean isInit() {
        return isInit;
    }

    public void init(TraceConfig config) {
        //不是主线程 就抛出异常
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            throw new AssertionError("must be init in main thread!");
        }
        this.config = config;
        //从当前线程中获取到 Choreographer 对象
        choreographer = Choreographer.getInstance();
        // 获得 Choreographer 里的 mLock锁 对象
        callbackQueueLock = reflectObject(choreographer, "mLock");
        // 获得 Choreographer 里的 mCallbackQueues 对象
        callbackQueues = reflectObject(choreographer, "mCallbackQueues");

        //反射获得 callbackQueues 中第一个 CallbackQueue对象的 addCallbackLocked 的方法
        // 第一个 CallbackQueue 是处理 input事件的？
        addInputQueue = reflectChoreographerMethod(callbackQueues[CALLBACK_INPUT], ADD_CALLBACK, long.class, Object.class, Object.class);
        //反射获得 callbackQueues 中第二个 CallbackQueue对象的 addCallbackLocked 的方法
        // 第二个 CallbackQueue 是处理 动画的？
        addAnimationQueue = reflectChoreographerMethod(callbackQueues[CALLBACK_ANIMATION], ADD_CALLBACK, long.class, Object.class, Object.class);
        //反射获得 callbackQueues 中第三个 CallbackQueue对象的 addCallbackLocked 的方法
        // 第三个 CallbackQueue 是绘制完 用于回调的？
        addTraversalQueue = reflectChoreographerMethod(callbackQueues[CALLBACK_TRAVERSAL], ADD_CALLBACK, long.class, Object.class, Object.class);

        // 获取 choreographer 中mFrameIntervalNanos 的值 并赋值给 frameIntervalNanos
        frameIntervalNanos = reflectObject(choreographer, "mFrameIntervalNanos");

        //注册一个 LooperDispatchListener
        LooperMonitor.register(new LooperMonitor.LooperDispatchListener() {
            @Override
            public boolean isValid() {
                return isAlive;
            }

            @Override
            public void dispatchStart() {
                super.dispatchStart();
                UIThreadMonitor.this.dispatchBegin();
            }

            @Override
            public void dispatchEnd() {
                super.dispatchEnd();
                UIThreadMonitor.this.dispatchEnd();
            }

        });
        this.isInit = true;
        MatrixLog.i(TAG, "[UIThreadMonitor] %s %s %s %s %s frameIntervalNanos:%s", callbackQueueLock == null, callbackQueues == null, addInputQueue == null, addTraversalQueue == null, addAnimationQueue == null, frameIntervalNanos);

        //如果是开发环境 就立即启动 ，而且在添加一个 LooperObserver 用于打印log
        if (config.isDevEnv()) {
            addObserver(new LooperObserver() {
                @Override
                public void doFrame(String focusedActivityName, long start, long end, long frameCostMs, long inputCost, long animationCost, long traversalCost) {
                    MatrixLog.i(TAG, "activityName[%s] frame cost:%sms [%s|%s|%s]ns", focusedActivityName, frameCostMs, inputCost, animationCost, traversalCost);
                }
            });
        }
    }

    private synchronized void addFrameCallback(int type, Runnable callback, boolean isAddHeader) {
        if (callbackExist[type]) {//type 类型的 callback已经存在 不重复添加
            MatrixLog.w(TAG, "[addFrameCallback] this type %s callback has exist! isAddHeader:%s", type, isAddHeader);
            return;
        }

        if (!isAlive && type == CALLBACK_INPUT) {
            MatrixLog.w(TAG, "[addFrameCallback] UIThreadMonitor is not alive!");
            return;
        }
        try {
            synchronized (callbackQueueLock) {//和 Choreographer 中使用相同的 锁对象 都是 mLock
                Method method = null;
                switch (type) {
                    case CALLBACK_INPUT:
                        method = addInputQueue;
                        break;
                    case CALLBACK_ANIMATION:
                        method = addAnimationQueue;
                        break;
                    case CALLBACK_TRAVERSAL:
                        method = addTraversalQueue;
                        break;
                }
                if (null != method) {
                    //执行 addCallbackLocked 方法
                    method.invoke(callbackQueues[type], !isAddHeader ? SystemClock.uptimeMillis() : -1, callback, null);
                    callbackExist[type] = true;
                }
            }
        } catch (Exception e) {
            MatrixLog.e(TAG, e.toString());
        }
    }

    public long getFrameIntervalNanos() {
        return frameIntervalNanos;
    }

    public void addObserver(LooperObserver observer) {
        if (!isAlive) {
            onStart();
        }
        synchronized (observers) {
            observers.add(observer);
        }
    }

    public void removeObserver(LooperObserver observer) {
        synchronized (observers) {
            observers.remove(observer);
            if (observers.isEmpty()) {
                onStop();
            }
        }
    }

    public long getQueueCost(int type, long token) {
        if (token != this.token) {
            return -1;
        }
        return queueStatus[type] == DO_QUEUE_END ? queueCost[type] : 0;
    }

    private <T> T reflectObject(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(instance);
        } catch (Exception e) {
            e.printStackTrace();
            MatrixLog.e(TAG, e.toString());
        }
        return null;
    }

    private Method reflectChoreographerMethod(Object instance, String name, Class<?>... argTypes) {
        try {
            Method method = instance.getClass().getDeclaredMethod(name, argTypes);
            method.setAccessible(true);
            return method;
        } catch (Exception e) {
            MatrixLog.e(TAG, e.toString());
        }
        return null;
    }

    private void dispatchBegin() {
        token = dispatchTimeMs[0] = SystemClock.uptimeMillis();
        dispatchTimeMs[2] = SystemClock.currentThreadTimeMillis();
        AppMethodBeat.i(AppMethodBeat.METHOD_ID_DISPATCH);

        synchronized (observers) {
            for (LooperObserver observer : observers) {
                if (!observer.isDispatchBegin()) {
                    observer.dispatchBegin(dispatchTimeMs[0], dispatchTimeMs[2], token);
                }
            }
        }
    }

    private void doFrameBegin(long token) {
        this.isBelongFrame = true;
    }

    private void doFrameEnd(long token) {

        doQueueEnd(CALLBACK_TRAVERSAL);

        for (int i : queueStatus) {
            if (i != DO_QUEUE_END) {
                queueCost[i] = DO_QUEUE_END_ERROR;
                if (config.isDevEnv) {
                    throw new RuntimeException(String.format("UIThreadMonitor happens type[%s] != DO_QUEUE_END", i));
                }
            }
        }
        queueStatus = new int[CALLBACK_LAST + 1];

        addFrameCallback(CALLBACK_INPUT, this, true);

        this.isBelongFrame = false;
    }

    private void dispatchEnd() {

        if (isBelongFrame) {
            doFrameEnd(token);
        }

        long start = token;
        long end = SystemClock.uptimeMillis();

        synchronized (observers) {
            for (LooperObserver observer : observers) {
                if (observer.isDispatchBegin()) {
                    observer.doFrame(AppMethodBeat.getVisibleScene(), token, SystemClock.uptimeMillis(), isBelongFrame ? end - start : 0, queueCost[CALLBACK_INPUT], queueCost[CALLBACK_ANIMATION], queueCost[CALLBACK_TRAVERSAL]);
                }
            }
        }

        dispatchTimeMs[3] = SystemClock.currentThreadTimeMillis();
        dispatchTimeMs[1] = SystemClock.uptimeMillis();

        AppMethodBeat.o(AppMethodBeat.METHOD_ID_DISPATCH);

        synchronized (observers) {
            for (LooperObserver observer : observers) {
                if (observer.isDispatchBegin()) {
                    observer.dispatchEnd(dispatchTimeMs[0], dispatchTimeMs[2], dispatchTimeMs[1], dispatchTimeMs[3], token, isBelongFrame);
                }
            }
        }

    }

    private void doQueueBegin(int type) {
        queueStatus[type] = DO_QUEUE_BEGIN;
        queueCost[type] = System.nanoTime();
    }

    private void doQueueEnd(int type) {
        queueStatus[type] = DO_QUEUE_END;
        queueCost[type] = System.nanoTime() - queueCost[type];
        synchronized (this) {
            callbackExist[type] = false;
        }
    }

    @Override
    public synchronized void onStart() {
        if (!isInit) {
            throw new RuntimeException("never init!");
        }
        if (!isAlive) {
            this.isAlive = true;
            synchronized (this) {
                MatrixLog.i(TAG, "[onStart] callbackExist:%s %s", Arrays.toString(callbackExist), Utils.getStack());
                callbackExist = new boolean[CALLBACK_LAST + 1];
            }
            queueStatus = new int[CALLBACK_LAST + 1];
            queueCost = new long[CALLBACK_LAST + 1];
            addFrameCallback(CALLBACK_INPUT, this, true);
        }
    }

    @Override
    public void run() {
        final long start = System.nanoTime();
        try {
            doFrameBegin(token);
            doQueueBegin(CALLBACK_INPUT);

            addFrameCallback(CALLBACK_ANIMATION, new Runnable() {

                @Override
                public void run() {
                    doQueueEnd(CALLBACK_INPUT);
                    doQueueBegin(CALLBACK_ANIMATION);
                }
            }, true);

            addFrameCallback(CALLBACK_TRAVERSAL, new Runnable() {

                @Override
                public void run() {
                    doQueueEnd(CALLBACK_ANIMATION);
                    doQueueBegin(CALLBACK_TRAVERSAL);
                }
            }, true);

        } finally {
            if (config.isDevEnv()) {
                MatrixLog.d(TAG, "[UIThreadMonitor#run] inner cost:%sns", System.nanoTime() - start);
            }
        }
    }


    @Override
    public synchronized void onStop() {
        if (!isInit) {
            throw new RuntimeException("UIThreadMonitor is never init!");
        }
        if (isAlive) {
            this.isAlive = false;
            MatrixLog.i(TAG, "[onStop] callbackExist:%s %s", Arrays.toString(callbackExist), Utils.getStack());
        }
    }

    @Override
    public boolean isAlive() {
        return isAlive;
    }


}

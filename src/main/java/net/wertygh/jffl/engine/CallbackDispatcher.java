package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.CallbackInfo;
import net.wertygh.jffl.api.CallbackInfoReturnable;
import net.wertygh.jffl.api.annotation.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public class CallbackDispatcher {
    private static Logger LOGGER = LoggerFactory.getLogger(CallbackDispatcher.class);
    private static int INITIAL_CAP = 64;
    private static Object[] NO_ARGS = new Object[0];
    public static volatile Handler[] DIRECT = new Handler[INITIAL_CAP];
    private static Object GROW_LOCK = new Object();
    private static int nextId = 0;
    private static ConcurrentHashMap<DedupKey, Integer> DEDUP = new ConcurrentHashMap<>();
    private static ThreadLocal<String> DISPATCH_STATE_KEY = new ThreadLocal<>();
    private static ThreadLocal<Deque<StateFrame>> STATE_FRAMES = ThreadLocal.withInitial(ArrayDeque::new);
    private static class DedupKey {
        public Object instance;
        public Method method;
        public boolean returnable;

        DedupKey(Object instance, Method method, boolean returnable) {
            this.instance = instance;
            this.method = method;
            this.returnable = returnable;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DedupKey k)) return false;
            return returnable == k.returnable
                    && Objects.equals(instance, k.instance)
                    && Objects.equals(method, k.method);
        }

        @Override public int hashCode() {
            return Objects.hash(instance, method, returnable);
        }
    }

    private static class Binding {
        public boolean includeSelf;
        public boolean includeArgsArray;
        public int boundArgCount;

        Binding(boolean includeSelf, boolean includeArgsArray, int boundArgCount) {
            this.includeSelf = includeSelf;
            this.includeArgsArray = includeArgsArray;
            this.boundArgCount = boundArgCount;
        }
    }

    private static class StateFrame {
        public String key;
        public Map<StateSlot, Object> values;

        StateFrame(String key, Map<StateSlot, Object> values) {
            this.key = key;
            this.values = values;
        }
    }

    private static class StateSlot {
        public String name;
        public Class<?> type;

        StateSlot(String name, Class<?> type) {
            this.name = name;
            this.type = type;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StateSlot s)) return false;
            return Objects.equals(name, s.name) && Objects.equals(type, s.type);
        }

        @Override public int hashCode() {
            return Objects.hash(name, type);
        }
    }

    private static class StateSpec {
        public String name;
        public Class<?> type;

        StateSpec(String name, Class<?> type) {
            this.name = name;
            this.type = type;
        }
    }
    private static Binding NO_BINDING = new Binding(false, false, 0);
    private static Binding SELF_ONLY = new Binding(true, false, 0);
    private static Binding ARGS_ONLY = new Binding(false, true, 0);
    private static Binding SELF_AND_ARGS = new Binding(true, true, 0);

    @FunctionalInterface public interface BoundSimple {
        void call(Object self, CallbackInfo ci) throws Throwable;
    }

    @FunctionalInterface public interface BoundArgs {
        void call(Object self, Object[] args, CallbackInfo ci) throws Throwable;
    }

    @FunctionalInterface private interface BoundCallback {
        void call(Object self, Object[] args, CallbackInfo ci) throws Throwable;
    }

    @FunctionalInterface public interface BoundWrap {
        Object call(Object self, Object[] args, Callable<?> original) throws Throwable;
    }

    public static class Handler {
        public Object instance;
        public boolean returnable;
        public boolean acceptsArgs;
        public boolean needsState;
        BoundCallback callback;
        BoundWrap wrap;

        private Handler(Object instance, boolean returnable, boolean acceptsArgs, boolean needsState, BoundCallback callback, BoundWrap wrap) {
            this.instance = instance;
            this.returnable = returnable;
            this.acceptsArgs = acceptsArgs;
            this.needsState = needsState;
            this.callback = callback;
            this.wrap = wrap;
        }

        public void fire(Object self, CallbackInfo ci) {
            try {
                if (callback != null) callback.call(self, NO_ARGS, ci);
            } catch (Throwable t) {
                logFail(t);
            }
        }

        public void fireArgs(Object self, Object[] a, CallbackInfo ci) {
            try {
                if (callback != null) callback.call(self, a == null ? NO_ARGS : a, ci);
            } catch (Throwable t) {
                logFail(t);
            }
        }

        public Object fireWrap(Object self, Object[] a, Callable<?> original) throws Throwable {
            return wrap.call(self, a == null ? NO_ARGS : a, original);
        }

        private static void logFail(Throwable t) {
            LOGGER.error("回调处理程序抛出异常", t);
        }
    }

    public static int register(Object instance, Method method, boolean returnable) {
        method.setAccessible(true);
        DedupKey key = new DedupKey(instance, method, returnable);
        Integer cached = DEDUP.get(key);
        if (cached != null) return cached;
        synchronized (GROW_LOCK) {
            cached = DEDUP.get(key);
            if (cached != null) return cached;
            Class<?>[] params = method.getParameterTypes();
            Handler h = buildHandler(instance, method, params, returnable);
            int id = nextId++;
            ensureCapacity(id);
            DIRECT[id] = h;
            DEDUP.put(key, id);
            return id;
        }
    }

    public static boolean handlerAcceptsArgs(int id) {
        return DIRECT[id].acceptsArgs;
    }

    public static boolean handlerNeedsState(int id) {
        return DIRECT[id].needsState;
    }

    public static boolean methodNeedsState(Method method) {
        Class<?>[] params = method.getParameterTypes();
        int terminal = params.length - 1;
        if (terminal < 0) return false;
        Annotation[][] annotations = method.getParameterAnnotations();
        for (int i=0;i<terminal;i++) {
            if (findStateAnnotation(annotations[i]) != null) return true;
        }
        return false;
    }

    private static Handler buildHandler(Object instance, Method m, Class<?>[] params, boolean returnable) {
        boolean wrapMethod = isWrapMethod(params);
        boolean needsState = methodNeedsState(m);
        boolean acceptsArgs = countBindablePayloadParams(m, wrapMethod) > 0;
        if (wrapMethod) {
            return new Handler(instance, returnable, acceptsArgs, needsState, null, buildWrap(instance, m));
        }
        return new Handler(instance, returnable, acceptsArgs, needsState, buildCallback(instance, m), null);
    }

    private static boolean isWrapMethod(Class<?>[] params) {
        return params.length >= 1 && params[params.length - 1] == Callable.class;
    }

    private static void ensureCapacity(int id) {
        Handler[] cur = DIRECT;
        if (id < cur.length) return;
        int newLen = Math.max(cur.length * 2, id + 16);
        Handler[] grown = new Handler[newLen];
        System.arraycopy(cur, 0, grown, 0, cur.length);
        DIRECT = grown;
    }

    public static void dispatch(int id, Object self, CallbackInfo ci) {
        DIRECT[id].fire(self, ci);
    }

    public static void dispatchWithArgs(int id, Object self, Object[] args, CallbackInfo ci) {
        DIRECT[id].fireArgs(self, args, ci);
    }

    public static <T> CallbackInfoReturnable<T> dispatchReturnable(int id, Object self, CallbackInfoReturnable<T> cir) {
        DIRECT[id].fire(self, cir);
        return cir;
    }

    public static <T> CallbackInfoReturnable<T> dispatchReturnableWithArgs(int id, Object self, Object[] args, CallbackInfoReturnable<T> cir) {
        DIRECT[id].fireArgs(self, args, cir);
        return cir;
    }

    public static void dispatchState(int id, String stateKey, Object self, CallbackInfo ci) {
        boolean temporary = beginDispatchState(stateKey);
        String previous = switchDispatchState(stateKey);
        try {
            DIRECT[id].fire(self, ci);
        } finally {
            restoreDispatchState(previous);
            endDispatchState(stateKey, temporary);
        }
    }

    public static void dispatchWithArgsState(int id, String stateKey, Object self, Object[] args, CallbackInfo ci) {
        boolean temporary = beginDispatchState(stateKey);
        String previous = switchDispatchState(stateKey);
        try {
            DIRECT[id].fireArgs(self, args, ci);
        } finally {
            restoreDispatchState(previous);
            endDispatchState(stateKey, temporary);
        }
    }

    public static <T> CallbackInfoReturnable<T> dispatchReturnableState(int id, String stateKey, Object self, CallbackInfoReturnable<T> cir) {
        boolean temporary = beginDispatchState(stateKey);
        String previous = switchDispatchState(stateKey);
        try {
            DIRECT[id].fire(self, cir);
            return cir;
        } finally {
            restoreDispatchState(previous);
            endDispatchState(stateKey, temporary);
        }
    }

    public static <T> CallbackInfoReturnable<T> dispatchReturnableWithArgsState(int id, String stateKey, Object self, Object[] args, CallbackInfoReturnable<T> cir) {
        boolean temporary = beginDispatchState(stateKey);
        String previous = switchDispatchState(stateKey);
        try {
            DIRECT[id].fireArgs(self, args, cir);
            return cir;
        } finally {
            restoreDispatchState(previous);
            endDispatchState(stateKey, temporary);
        }
    }


    @SuppressWarnings("unchecked")
    public static <T> void dispatchWrap(int id, Object self, Object[] args, Callable<?> original, CallbackInfoReturnable<T> cir) {
        Handler h = DIRECT[id];
        Throwable wrapFailure = null;
        try {
            cir.setReturnValue((T) h.fireWrap(self, args, original));
            return;
        } catch (Throwable t) {
            wrapFailure = t;
            LOGGER.error("包装处理程序为ID {}抛出异常, 回退到original", id, t);
        }
        try {
            cir.setReturnValue((T) original.call());
        } catch (Exception fallback) {
            LOGGER.error("回退原始调用也失败", fallback);
            if (wrapFailure instanceof RuntimeException re) throw re;
            if (wrapFailure instanceof Error er) throw er;
            RuntimeException re = new RuntimeException(
                    "WrapOperation处理器与原始调用均失败, 你确认原始方法是正常的?", wrapFailure);
            re.addSuppressed(fallback);
            throw re;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> void dispatchWrapState(int id, String stateKey, Object self, Object[] args, Callable<?> original, CallbackInfoReturnable<T> cir) {
        boolean temporary = beginDispatchState(stateKey);
        String previous = switchDispatchState(stateKey);
        try {
            dispatchWrap(id, self, args, original, cir);
        } finally {
            restoreDispatchState(previous);
            endDispatchState(stateKey, temporary);
        }
    }

    public static void enterStateFrame(String key) {
        if (key == null || key.isEmpty()) return;
        STATE_FRAMES.get().push(new StateFrame(key, new HashMap<>()));
    }

    public static void enterStateFrameIfAbsent(String key) {
        if (key == null || key.isEmpty()) return;
        Deque<StateFrame> frames = STATE_FRAMES.get();
        StateFrame current = frames.peek();
        if (current == null || !key.equals(current.key)) {
            enterStateFrame(key);
        }
    }

    public static void exitStateFrame(String key) {
        if (key == null || key.isEmpty()) return;
        Deque<StateFrame> frames = STATE_FRAMES.get();
        StateFrame current = frames.peek();
        if (current != null && key.equals(current.key)) {
            frames.pop();
            cleanupFrames(frames);
            return;
        }
        for (Iterator<StateFrame> it = frames.iterator(); it.hasNext();) {
            if (key.equals(it.next().key)) {
                it.remove();
                break;
            }
        }
        cleanupFrames(frames);
    }

    public static void clearAllStateFrames() {
        STATE_FRAMES.remove();
        DISPATCH_STATE_KEY.remove();
    }

    private static boolean beginDispatchState(String key) {
        if (key == null || key.isEmpty()) return false;
        if (findFrame(key) != null) return false;
        enterStateFrame(key);
        return true;
    }

    private static void endDispatchState(String key, boolean temporary) {
        if (temporary) exitStateFrame(key);
    }

    private static String switchDispatchState(String key) {
        String previous = DISPATCH_STATE_KEY.get();
        if (key == null || key.isEmpty()) DISPATCH_STATE_KEY.remove();
        else DISPATCH_STATE_KEY.set(key);
        return previous;
    }

    private static void restoreDispatchState(String previous) {
        if (previous == null) DISPATCH_STATE_KEY.remove();
        else DISPATCH_STATE_KEY.set(previous);
    }

    private static StateFrame findFrame(String key) {
        if (key == null || key.isEmpty()) return null;
        for (StateFrame frame : STATE_FRAMES.get()) {
            if (key.equals(frame.key)) return frame;
        }
        return null;
    }

    private static void cleanupFrames(Deque<StateFrame> frames) {
        if (frames.isEmpty()) STATE_FRAMES.remove();
    }

    public static Callable<?> createOriginalCaller(Object targetOrClass, String methodName, Object[] args) {
        return () -> {
            boolean staticCall = targetOrClass instanceof Class<?>;
            Class<?> clazz = staticCall ? (Class<?>) targetOrClass : targetOrClass.getClass();
            Object receiver = staticCall ? null : targetOrClass;
            Method best = null;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != args.length) continue;
                if (Modifier.isStatic(m.getModifiers()) != staticCall) continue;
                if (!parametersCompatible(m.getParameterTypes(), args)) continue;
                best = m;
                break;
            }
            if (best == null) {
                throw new NoSuchMethodException("在" + clazz.getName() + "上没有兼容的方法"
                        + methodName + "(" + args.length + "参)");
            }
            best.setAccessible(true);
            return best.invoke(receiver, args);
        };
    }

    private static boolean parametersCompatible(Class<?>[] parameterTypes, Object[] args) {
        for (int i=0;i<parameterTypes.length;i++) {
            if (!isValueCompatible(parameterTypes[i], args[i])) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    private static boolean isExactSimple(Method m) {
        Class<?>[] pt = m.getParameterTypes();
        return pt.length == 2
                && pt[0] == Object.class
                && CallbackInfo.class.isAssignableFrom(pt[1]);
    }

    private static boolean isExactArgs(Method m) {
        Class<?>[] pt = m.getParameterTypes();
        return pt.length == 3
                && pt[0] == Object.class
                && pt[1] == Object[].class
                && CallbackInfo.class.isAssignableFrom(pt[2]);
    }

    private static boolean isExactWrap(Method m) {
        Class<?>[] pt = m.getParameterTypes();
        return pt.length == 3
                && pt[0] == Object.class
                && pt[1] == Object[].class
                && pt[2] == Callable.class;
    }

    private static BoundCallback buildCallback(Object instance, Method m) {
        if (!Modifier.isStatic(m.getModifiers()) && isExactSimple(m)) {
            BoundSimple s = buildSimple(instance, m);
            if (s != null) return (self, args, ci) -> s.call(self, ci);
        }
        if (!Modifier.isStatic(m.getModifiers()) && isExactArgs(m)) {
            BoundArgs a = buildArgs(instance, m);
            if (a != null) return a::call;
        }
        return buildAdaptiveCallback(instance, m);
    }

    private static BoundCallback buildAdaptiveCallback(Object instance, Method m) {
        Class<?>[] params = m.getParameterTypes();
        StateSpec[] stateSpecs = collectStateSpecs(m, params.length - 1);
        Class<?>[] callbackCore = bindableCoreTypes(params, stateSpecs, params.length - 1);
        return (self, args, ci) -> {
            Object[] safeArgs = args == null ? NO_ARGS : args;
            Binding binding = resolveBinding(callbackCore, safeArgs);
            Object[] boundCore = buildCoreArguments(binding, self, safeArgs);
            Object[] invokeArgs = buildInvokeArguments(params.length, params.length - 1, stateSpecs, boundCore, ci);
            invokeReflective(m, instance, invokeArgs);
        };
    }

    private static BoundWrap buildWrap(Object instance, Method m) {
        if (!methodNeedsState(m) && !Modifier.isStatic(m.getModifiers()) && isExactWrap(m)) {
            BoundWrap fast = buildLegacyWrap(instance, m);
            if (fast != null) return fast;
        }
        Class<?>[] params = m.getParameterTypes();
        StateSpec[] stateSpecs = collectStateSpecs(m, params.length - 1);
        Class<?>[] callbackCore = bindableCoreTypes(params, stateSpecs, params.length - 1);
        return (self, args, original) -> {
            Object[] safeArgs = args == null ? NO_ARGS : args;
            Binding binding = resolveBinding(callbackCore, safeArgs);
            Object[] boundCore = buildCoreArguments(binding, self, safeArgs);
            Object[] invokeArgs = buildInvokeArguments(params.length, params.length - 1, stateSpecs, boundCore, original);
            return invokeReflective(m, instance, invokeArgs);
        };
    }

    private static Binding resolveBinding(Class<?>[] callbackCore, Object[] args) {
        int coreLen = callbackCore.length;
        int argLen = args.length;
        if (coreLen == 0) return NO_BINDING;
        if (coreLen == 2 && callbackCore[0] == Object.class && callbackCore[1] == Object[].class) {
            return SELF_AND_ARGS;
        }
        if (coreLen == 1 && callbackCore[0] == Object[].class) {
            return ARGS_ONLY;
        }
        if (coreLen == 1 && callbackCore[0] == Object.class) {
            if (argLen == 1 && isValueCompatible(callbackCore[0], args[0])) {
                return new Binding(false, false, 1);
            }
            return SELF_ONLY;
        }
        if (coreLen == argLen && compatiblePrefix(callbackCore, 0, args, coreLen)) {
            return new Binding(false, false, coreLen);
        }
        if (coreLen == argLen + 1
                && callbackCore[0] == Object.class
                && compatiblePrefix(callbackCore, 1, args, argLen)) {
            return new Binding(true, false, argLen);
        }
        if (coreLen > 0 && coreLen < argLen && compatiblePrefix(callbackCore, 0, args, coreLen)) {
            return new Binding(false, false, coreLen);
        }
        if (coreLen > 1
                && callbackCore[0] == Object.class
                && coreLen - 1 <= argLen
                && compatiblePrefix(callbackCore, 1, args, coreLen - 1)) {
            return new Binding(true, false, coreLen - 1);
        }
        throw new IllegalArgumentException("无法将回调参数签名适配到目标实参: core="
                + describe(callbackCore) + ", args=" + describeArgs(args));
    }

    private static boolean compatiblePrefix(Class<?>[] callbackCore, int coreOffset, Object[] args, int count) {
        for (int i=0;i<count;i++) {
            if (!isValueCompatible(callbackCore[coreOffset + i], args[i])) return false;
        }
        return true;
    }

    private static boolean isValueCompatible(Class<?> expectedType, Object value) {
        if (value == null) return !expectedType.isPrimitive();
        return wrap(expectedType).isInstance(value);
    }

    private static Object[] buildCoreArguments(Binding binding, Object self, Object[] args) {
        int totalCount = (binding.includeSelf ? 1 : 0)
                + (binding.includeArgsArray ? 1 : 0)
                + binding.boundArgCount;
        Object[] coreArgs = new Object[totalCount];
        int idx = 0;
        if (binding.includeSelf) {
            coreArgs[idx++] = self;
        }
        if (binding.includeArgsArray) {
            coreArgs[idx++] = args;
        }
        for (int i=0;i<binding.boundArgCount;i++) {
            coreArgs[idx++] = args[i];
        }
        return coreArgs;
    }

    private static Object[] buildInvokeArguments(int totalCount, int terminalIndex, StateSpec[] stateSpecs, Object[] boundCore, Object terminal) throws ReflectiveOperationException {
        Object[] invokeArgs = new Object[totalCount];
        int coreIdx = 0;
        for (int i=0;i<terminalIndex;i++) {
            StateSpec state = stateSpecs[i];
            if (state != null) {
                invokeArgs[i] = stateValue(state);
            } else {
                invokeArgs[i] = boundCore[coreIdx++];
            }
        }
        invokeArgs[terminalIndex] = terminal;
        return invokeArgs;
    }

    private static int countBindablePayloadParams(Method method, boolean wrapMethod) {
        Class<?>[] params = method.getParameterTypes();
        int terminal = params.length - 1;
        if (terminal < 0) return 0;
        StateSpec[] stateSpecs = collectStateSpecs(method, terminal);
        return bindableCoreTypes(params, stateSpecs, terminal).length;
    }

    private static StateSpec[] collectStateSpecs(Method method, int terminalIndex) {
        Class<?>[] params = method.getParameterTypes();
        StateSpec[] result = new StateSpec[params.length];
        if (terminalIndex <= 0) return result;
        Annotation[][] annotations = method.getParameterAnnotations();
        for (int i=0;i<terminalIndex;i++) {
            State state = findStateAnnotation(annotations[i]);
            if (state == null) continue;
            String name = state.value().isEmpty() ? params[i].getName() : state.value();
            result[i] = new StateSpec(name, params[i]);
        }
        return result;
    }

    private static State findStateAnnotation(Annotation[] annotations) {
        if (annotations == null) return null;
        for (Annotation annotation : annotations) {
            if (annotation instanceof State state) return state;
        }
        return null;
    }

    private static Class<?>[] bindableCoreTypes(Class<?>[] params, StateSpec[] stateSpecs, int terminalIndex) {
        int count = 0;
        for (int i=0;i<terminalIndex;i++) {
            if (stateSpecs[i] == null) count++;
        }
        Class<?>[] result = new Class<?>[count];
        int out = 0;
        for (int i=0;i<terminalIndex;i++) {
            if (stateSpecs[i] == null) result[out++] = params[i];
        }
        return result;
    }

    private static Object stateValue(StateSpec spec) throws ReflectiveOperationException {
        String key = DISPATCH_STATE_KEY.get();
        if (key == null || key.isEmpty()) {
            key = "global";
            DISPATCH_STATE_KEY.set(key);
        }
        StateFrame frame = findFrame(key);
        if (frame == null) {
            enterStateFrame(key);
            frame = findFrame(key);
        }
        StateSlot slot = new StateSlot(spec.name, spec.type);
        Object existing = frame.values.get(slot);
        if (existing != null) return existing;
        Object created = createStateInstance(spec.type);
        Object raced = frame.values.putIfAbsent(slot, created);
        return raced != null ? raced : created;
    }

    private static Object createStateInstance(Class<?> type) throws ReflectiveOperationException {
        if (type == Map.class) return new ConcurrentHashMap<>();
        if (type == java.util.concurrent.ConcurrentMap.class) return new ConcurrentHashMap<>();
        if (type == java.util.List.class) return new java.util.concurrent.CopyOnWriteArrayList<>();
        if (type == Set.class) return ConcurrentHashMap.newKeySet();
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("@State参数不能是基本类型: " + type.getName());
        }
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static Object invokeReflective(Method method, Object instance, Object[] invokeArgs) throws Throwable {
        try {
            return method.invoke(instance, invokeArgs);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    private static String describe(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<types.length;i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].getName());
        }
        return sb.append(']').toString();
    }

    private static String describeArgs(Object[] args) {
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<args.length;i++) {
            if (i > 0) sb.append(", ");
            Object arg = args[i];
            sb.append(arg == null ? "null" : arg.getClass().getName());
        }
        return sb.append(']').toString();
    }

    private static BoundSimple buildSimple(Object instance, Method m) {
        try {
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup());
            MethodHandle mh = lk.unreflect(m);
            Class<?>[] pt = m.getParameterTypes();
            MethodType sam = MethodType.methodType(void.class, Object.class, CallbackInfo.class);
            MethodType instantiated = MethodType.methodType(void.class, pt[0], pt[pt.length - 1]);
            CallSite cs = LambdaMetafactory.metafactory(
                    lk, "call",
                    MethodType.methodType(BoundSimple.class, m.getDeclaringClass()),
                    sam, mh, instantiated);
            return (BoundSimple) cs.getTarget().invoke(instance);
        } catch (Throwable t) {
            LOGGER.debug("BoundSimple LMF失败: {}/{}", m, t.getMessage());
            return null;
        }
    }

    private static BoundArgs buildArgs(Object instance, Method m) {
        try {
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup());
            MethodHandle mh = lk.unreflect(m);
            MethodType sam = MethodType.methodType(void.class, Object.class, Object[].class, CallbackInfo.class);
            CallSite cs = LambdaMetafactory.metafactory(
                    lk, "call",
                    MethodType.methodType(BoundArgs.class, m.getDeclaringClass()),
                    sam, mh, sam);
            return (BoundArgs) cs.getTarget().invoke(instance);
        } catch (Throwable t) {
            LOGGER.debug("BoundArgs LMF失败: {}/{}", m, t.getMessage());
            return null;
        }
    }

    private static BoundWrap buildLegacyWrap(Object instance, Method m) {
        try {
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup());
            MethodHandle mh = lk.unreflect(m);
            MethodHandle bound = mh.bindTo(instance);
            MethodHandle adapted = bound.asType(MethodType.methodType(Object.class, Object.class, Object[].class, Callable.class));
            return (s, a, o) -> adapted.invokeExact(s, a, o);
        } catch (Throwable t) {
            LOGGER.debug("BoundWrap LMF失败: {}/{}", m, t.getMessage());
            return null;
        }
    }
}

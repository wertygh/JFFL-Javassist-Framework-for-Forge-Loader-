package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.CallbackInfo;
import net.wertygh.jffl.api.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class CallbackDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallbackDispatcher.class);
    private static final ConcurrentHashMap<Integer, Handler> HANDLERS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    @FunctionalInterface
    public interface CallbackInvoker {
        void invoke(Object instance, Object self, CallbackInfo ci) throws Throwable;
    }

    @FunctionalInterface
    public interface ArgsCallbackInvoker {
        void invoke(Object instance, Object self, Object[] args, CallbackInfo ci) throws Throwable;
    }

    @FunctionalInterface
    public interface WrapInvoker {
        Object invoke(Object instance, Object self, Object[] args, Callable<?> original) throws Throwable;
    }

    @FunctionalInterface
    interface BoundCallback {
        void call(Object self, CallbackInfo ci) throws Throwable;
    }

    @FunctionalInterface
    interface BoundArgsCallback {
        void call(Object self, Object[] args, CallbackInfo ci) throws Throwable;
    }

    public record Handler(Object instance, CallbackInvoker invoker, ArgsCallbackInvoker argsInvoker, WrapInvoker wrapInvoker, boolean returnable, boolean acceptsArgs) {
        public Handler(Object instance, CallbackInvoker invoker, boolean returnable) {
            this(instance, invoker, null, null, returnable, false);
        }
    }
    
    public static int register(Object instance, Method method, boolean returnable) {
        int id = NEXT_ID.incrementAndGet();
        method.setAccessible(true);
        Class<?>[] params = method.getParameterTypes();
        boolean isWrap = params.length >= 3
                && params[params.length - 1] == Callable.class;
        if (isWrap) {
            WrapInvoker wrapInvoker = buildWrapInvoker(instance, method);
            if (wrapInvoker == null) {
                wrapInvoker = (inst, self, args, original) -> method.invoke(inst, self, args, original);
            }
            HANDLERS.put(id,new Handler(instance,null,null,wrapInvoker,returnable,false));
            return id;
        }
        boolean hasArgs = params.length >= 3
                && params[1] == Object[].class
                && CallbackInfo.class.isAssignableFrom(params[params.length - 1]);
        if (hasArgs) {
            ArgsCallbackInvoker argsInvoker = buildArgsInvokerViaMetafactory(instance, method);
            if (argsInvoker == null) {
                LOGGER.debug("LambdaMetafactory对于参数回调{}失败", method);
                argsInvoker = (inst, self, args, ci)->method.invoke(inst, self, args, ci);
            }
            HANDLERS.put(id,new Handler(instance,null,argsInvoker,null,returnable,true));
            return id;
        }
        CallbackInvoker invoker = buildInvokerViaMetafactory(instance, method);
        if (invoker == null) {
            LOGGER.debug("LambdaMetafactory对{}失败", method);
            invoker = (inst, self, ci) -> method.invoke(inst, self, ci);
        }
        HANDLERS.put(id, new Handler(instance, invoker, null, null, returnable, false));
        return id;
    }

    public static void dispatch(int id, Object self, CallbackInfo ci) {
        Handler h = HANDLERS.get(id);
        if (h == null) {LOGGER.error("未为ID {}注册回调处理程序", id);return;}
        try {
            h.invoker.invoke(h.instance, self, ci);
        } catch (Throwable t) {LOGGER.error("回调处理程序为ID {}抛出异常", id, t);}
    }

    public static void dispatchWithArgs(int id, Object self, Object[] args, CallbackInfo ci) {
        Handler h = HANDLERS.get(id);
        if (h == null) {LOGGER.error("未为ID {}注册回调处理程序", id);return;}
        try {
            if (h.acceptsArgs && h.argsInvoker != null) {
                h.argsInvoker.invoke(h.instance, self, args, ci);
            } else if (h.invoker != null) {
                h.invoker.invoke(h.instance, self, ci);
            }
        } catch (Throwable t) {LOGGER.error("回调处理程序为ID {}抛出异常", id, t);}
    }

    @SuppressWarnings("unchecked")
    public static <T> CallbackInfoReturnable<T> dispatchReturnable(int id, Object self, CallbackInfoReturnable<T> cir) {
        Handler h = HANDLERS.get(id);
        if (h == null) {LOGGER.error("未为ID {}注册回调处理程序", id);return cir;}
        try {
            h.invoker.invoke(h.instance, self, cir);
        } catch (Throwable t) {LOGGER.error("回调处理程序为ID {}抛出异常", id, t);}
        return cir;
    }

    @SuppressWarnings("unchecked")
    public static <T> CallbackInfoReturnable<T> dispatchReturnableWithArgs(int id, Object self, Object[] args, CallbackInfoReturnable<T> cir) {
        Handler h = HANDLERS.get(id);
        if (h == null) {LOGGER.error("未为ID {}注册回调处理程序", id);return cir;}
        try {
            if (h.acceptsArgs && h.argsInvoker != null) {
                h.argsInvoker.invoke(h.instance, self, args, cir);
            } else if (h.invoker != null) {
                h.invoker.invoke(h.instance, self, cir);
            }
        } catch (Throwable t) {LOGGER.error("回调处理程序为ID {}抛出异常", id, t);}
        return cir;
    }
    
    @SuppressWarnings("unchecked")
    public static <T> void dispatchWrap(int id, Object self, Object[] args, Callable<?> original, CallbackInfoReturnable<T> cir) {
        Handler h = HANDLERS.get(id);
        if (h == null) {
            LOGGER.error("未为ID {}注册换行处理程序", id);
            try {
                cir.setReturnValue((T) original.call());
            } catch (Exception e) {LOGGER.error("调用失败", e);}
            return;
        }
        try {
            Object result = h.wrapInvoker.invoke(h.instance, self, args, original);
            cir.setReturnValue((T) result);
        } catch (Throwable t) {
            LOGGER.error("包装处理程序为ID {}抛出异常", id, t);
            try {
                cir.setReturnValue((T) original.call());
            } catch (Exception e) {LOGGER.error("调用失败", e);}
        }
    }

    public static boolean handlerAcceptsArgs(int id) {
        Handler h = HANDLERS.get(id);
        return h != null && h.acceptsArgs;
    }
    
    @SuppressWarnings("unchecked")
    public static java.util.concurrent.Callable<?> createOriginalCaller(Object target, String methodName, Object[] args) {
        return () -> {
            Class<?> clazz = target.getClass();
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == args.length) {
                    m.setAccessible(true);
                    Class<?>[] paramTypes = m.getParameterTypes();
                    Object[] converted = new Object[args.length];
                    for (int i = 0; i < args.length; i++) {converted[i] = args[i];}
                    return m.invoke(target, converted);
                }
            }
            throw new NoSuchMethodException("在"+clazz.getName()+"上没有方法"+methodName+", 有"+args.length+"个参数");
        };
    }

    private static CallbackInvoker buildInvokerViaMetafactory(Object instance, Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
            MethodHandle mh = lookup.unreflect(method);
            MethodType samType = MethodType.methodType(void.class, Object.class, CallbackInfo.class);
            MethodType instantiatedType = MethodType.methodType(void.class, Object.class, CallbackInfo.class);
            MethodType factoryType = MethodType.methodType(BoundCallback.class, method.getDeclaringClass());
            CallSite cs = LambdaMetafactory.metafactory(
                    lookup,
                    "call",
                    factoryType,
                    samType,
                    mh,
                    instantiatedType
            );
            BoundCallback bound = (BoundCallback) cs.getTarget().invoke(instance);
            return (inst, self, ci) -> bound.call(self, ci);
        } catch (Throwable t) {
            LOGGER.debug("LambdaMetafactory生成失败, 原因：{}: {}", method, t.getMessage());
            return buildFallbackInvoker(instance, method);
        }
    }

    private static ArgsCallbackInvoker buildArgsInvokerViaMetafactory(Object instance, Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
            MethodHandle mh = lookup.unreflect(method);
            MethodType samType = MethodType.methodType(void.class, Object.class, Object[].class, CallbackInfo.class);
            MethodType instantiatedType = MethodType.methodType(void.class, Object.class, Object[].class, CallbackInfo.class);
            MethodType factoryType = MethodType.methodType(BoundArgsCallback.class, method.getDeclaringClass());
            CallSite cs = LambdaMetafactory.metafactory(
                    lookup,
                    "call",
                    factoryType,
                    samType,
                    mh,
                    instantiatedType
            );
            BoundArgsCallback bound = (BoundArgsCallback) cs.getTarget().invoke(instance);
            return (inst, self, args, ci) -> bound.call(self, args, ci);
        } catch (Throwable t) {
            LOGGER.debug("ArgsLambda元工厂失败{}: {}", method, t.getMessage());
            return null;
        }
    }

    private static WrapInvoker buildWrapInvoker(Object instance, Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
            MethodHandle mh = lookup.unreflect(method);
            MethodHandle bound = mh.bindTo(instance);
            MethodType target = MethodType.methodType(Object.class, Object.class, Object[].class, Callable.class);
            MethodHandle adapted = bound.asType(target);
            return (inst, self, args, original) -> adapted.invokeExact(self, args, original);
        } catch (Throwable t) {
            LOGGER.debug("WrapInvoker生成失败：{}：{}", method, t.getMessage());
            return null;
        }
    }

    private static CallbackInvoker buildFallbackInvoker(Object instance, Method method) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle mh = lookup.unreflect(method);
            MethodHandle bound = mh.bindTo(instance);
            MethodType boundSamType = MethodType.methodType(void.class, Object.class, CallbackInfo.class);
            MethodHandle adapted = bound.asType(boundSamType);
            return (inst, self, ci) -> adapted.invokeExact(self, ci);
        } catch (Throwable t) {
            LOGGER.debug("MethodHandle对于{}失败：{}", method, t.getMessage());
            return null;
        }
    }
}

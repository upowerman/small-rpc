package io.github.upowerman.core.spi;

import io.github.upowerman.core.invocation.Invocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自研 SPI 装载器（类 Dubbo，不用 JDK ServiceLoader）。
 * 登记文件：classpath 下 META-INF/small-rpc/{接口全限定名}，行格式 name=FQCN。
 * 双类加载器枚举（TCCL + 本类 CL）合并去重，兼容 Spring Boot 打包与 devtools。
 * 扩展实例懒加载单例；任何异常（缺文件/类型不符/同名不同实现）都大声失败，不静默吞。
 * 登记去重口径为 (name, FQCN)：同一实现经多个 URL 可见（target/classes + 本地 jar）按重复忽略。
 */
public final class SpiLoader<S> {

    private static final Logger logger = LoggerFactory.getLogger(SpiLoader.class);

    private static final String PREFIX = "META-INF/small-rpc/";

    private static final ConcurrentHashMap<Class<?>, SpiLoader<?>> LOADERS =
            new ConcurrentHashMap<Class<?>, SpiLoader<?>>();

    /** 正在构造中的实现类（SpiInject 环检测；单 loader 内 synchronized 串行，ThreadLocal 防递归重入） */
    private static final ThreadLocal<Set<Class<?>>> CONSTRUCTING =
            new ThreadLocal<Set<Class<?>>>() {
                @Override
                protected Set<Class<?>> initialValue() {
                    return new HashSet<Class<?>>();
                }
            };

    private final Class<S> type;
    private final Map<String, Class<S>> implClasses = new LinkedHashMap<String, Class<S>>();
    private final ConcurrentHashMap<String, S> singletons = new ConcurrentHashMap<String, S>();
    private volatile S adaptiveProxy;

    private SpiLoader(Class<S> type) {
        this.type = type;
        loadRegistrationFiles();
    }

    @SuppressWarnings("unchecked")
    public static <S> SpiLoader<S> of(Class<S> type) {
        if (type == null || !type.isInterface()) {
            throw new IllegalArgumentException("spi type must be an interface: " + type);
        }
        SpiLoader<?> existing = LOADERS.get(type);
        if (existing == null) {
            SpiLoader<?> created = new SpiLoader<Object>((Class<Object>) type);
            existing = LOADERS.putIfAbsent(type, created);
            if (existing == null) {
                existing = created;
            }
        }
        return (SpiLoader<S>) existing;
    }

    /** 按名取扩展（懒加载单例）；null/空名 = 默认扩展 */
    public S getExtension(String name) {
        if (name == null || name.isEmpty()) {
            return getDefaultExtension();
        }
        S instance = singletons.get(name);
        if (instance == null) {
            instance = createSingleton(name);
        }
        return instance;
    }

    /** 接口 @Spi value 指定的默认扩展 */
    public S getDefaultExtension() {
        Spi spi = type.getAnnotation(Spi.class);
        if (spi == null || spi.value().isEmpty()) {
            throw new IllegalStateException("no default @Spi name on interface " + type.getName());
        }
        return getExtension(spi.value());
    }

    public Set<String> getSupportedExtensions() {
        return new LinkedHashSet<String>(implClasses.keySet());
    }

    /**
     * 自适应分发器（单例）：方法调用时按 attachments[key] 选扩展再委托；
     * 键缺失/为空 → 默认扩展。要求接口至少有一个含 Invocation 参数的方法。
     */
    @SuppressWarnings("unchecked")
    public S getAdaptive() {
        boolean hasInvocationParameter = false;
        for (Method method : type.getMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType == Invocation.class) {
                    hasInvocationParameter = true;
                }
            }
        }
        if (!hasInvocationParameter) {
            throw new IllegalStateException(type.getName()
                    + " has no method taking an Invocation parameter; @Adaptive 不可用");
        }
        Adaptive adaptive = type.getAnnotation(Adaptive.class);
        if (adaptive == null) {
            throw new IllegalStateException(type.getName() + " is not annotated with @Adaptive");
        }
        S result = adaptiveProxy;
        if (result == null) {
            final String key = adaptive.value();
            result = (S) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (method.getDeclaringClass() == Object.class) {
                                // Object 方法必须按"代理自身"的 identity 语义实现：
                                // 转发给 SpiLoader 会破坏 equals 反身性（adaptive.equals(adaptive) == false）
                                String methodName = method.getName();
                                if ("equals".equals(methodName)) {
                                    return proxy == args[0];
                                }
                                if ("hashCode".equals(methodName)) {
                                    return System.identityHashCode(proxy);
                                }
                                if ("toString".equals(methodName)) {
                                    return "Adaptive(" + type.getName() + ")";
                                }
                                return method.invoke(SpiLoader.this, args);
                            }
                            Invocation invocation = findInvocation(method, args);
                            if (invocation == null) {
                                throw new IllegalStateException(
                                        "adaptive method must take an Invocation parameter: " + method);
                            }
                            Object raw = invocation.attachments() == null
                                    ? null : invocation.attachments().get(key);
                            String name = raw == null ? null : String.valueOf(raw);
                            Object target = getExtension(name);
                            return method.invoke(target, args);
                        }
                    });
            adaptiveProxy = result;
        }
        return result;
    }

    private static Invocation findInvocation(Method method, Object[] args) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == Invocation.class && args != null && args[i] != null) {
                return (Invocation) args[i];
            }
        }
        return null;
    }

    /**
     * 懒加载单例：<b>实例化不持本 loader 的监视器</b>。
     * 持锁实例化会串行化全部扩展创建，且两个 loader 的扩展互相 {@code @SpiInject} 时
     * 形成跨 loader 的 ABBA 死锁；改用 {@code putIfAbsent} 收敛并发重复创建
     * （竞态失败方的实例被丢弃，保证同名返回同一实例）。
     * 同线程环检测（{@link #CONSTRUCTING}）语义不变。
     */
    private S createSingleton(String name) {
        S instance = singletons.get(name);
        if (instance != null) {
            return instance;
        }
        Class<S> impl = implClasses.get(name);
        if (impl == null) {
            throw new IllegalStateException("no spi extension named '" + name + "' for "
                    + type.getName() + ", supported: " + joinNames());
        }
        S created = instantiate(impl);
        S previous = singletons.putIfAbsent(name, created);
        if (previous != null) {
            return previous;
        }
        logger.debug("spi extension instantiated: {} = {}", name, impl.getName());
        return created;
    }

    private S instantiate(Class<S> impl) {
        Set<Class<?>> visiting = CONSTRUCTING.get();
        if (!visiting.add(impl)) {
            throw new IllegalStateException("spi cycle detected: " + impl.getName()
                    + " is already being constructed (SpiInject 环依赖)");
        }
        try {
            S instance = impl.getConstructor().newInstance();
            injectFields(instance);
            return instance;
        } catch (ReflectiveOperationException | ExceptionInInitializerError | NoClassDefFoundError e) {
            throw new IllegalStateException("cannot instantiate spi extension " + impl.getName()
                    + " (需要公共无参构造器 / 静态初始化失败)", e);
        } finally {
            visiting.remove(impl);
        }
    }

    /** 装配 @SpiInject 字段：按字段类型注入该 SPI 的默认扩展（已有值不覆盖） */
    private void injectFields(S instance) throws IllegalAccessException {
        for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (!field.isAnnotationPresent(SpiInject.class)) {
                    continue;
                }
                field.setAccessible(true);
                if (field.get(instance) != null) {
                    continue;
                }
                Object dependency = SpiLoader.of(field.getType()).getDefaultExtension();
                field.set(instance, dependency);
            }
        }
    }

    private void loadRegistrationFiles() {
        String fileName = PREFIX + type.getName();
        try {
            Set<String> seenUrls = new HashSet<String>();
            ClassLoader[] loaders = new ClassLoader[]{
                    Thread.currentThread().getContextClassLoader(), SpiLoader.class.getClassLoader()};
            for (ClassLoader loader : loaders) {
                if (loader == null) {
                    continue;
                }
                Enumeration<URL> urls = loader.getResources(fileName);
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    if (seenUrls.add(url.toString())) {
                        parseFile(url, loader);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read spi registration file " + fileName, e);
        }
        if (implClasses.isEmpty()) {
            throw new IllegalStateException("no spi impl registered for " + type.getName()
                    + " (missing " + fileName + "?)");
        }
    }

    @SuppressWarnings("unchecked")
    private void parseFile(URL url, ClassLoader owner) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0 || eq == trimmed.length() - 1) {
                        throw new IllegalStateException("bad spi line in " + url + ": " + line);
                    }
                    String name = trimmed.substring(0, eq).trim();
                    String fqcn = trimmed.substring(eq + 1).trim();
                    Class<?> clazz = loadImpl(fqcn, owner, url);
                    if (!type.isAssignableFrom(clazz)) {
                        throw new IllegalStateException("spi impl " + fqcn + " does not implement "
                                + type.getName() + " (in " + url + ")");
                    }
                    Class<S> existing = implClasses.get(name);
                    if (existing != null) {
                        if (existing.getName().equals(fqcn)) {
                            // 同一实现经两个 URL 可见（target/classes + 本地 jar 同时在 classpath）：
                            // 正常开发场景，按 (name, FQCN) 去重，不视为冲突
                            logger.debug("duplicate spi registration ignored: {} = {} (in {})",
                                    name, fqcn, url);
                            continue;
                        }
                        throw new IllegalStateException("duplicate spi name '" + name + "' for "
                                + type.getName() + " registered to different impls: "
                                + existing.getName() + " vs " + fqcn + " (in " + url + ")");
                    }
                    implClasses.put(name, (Class<S>) clazz.asSubclass(type));
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read spi registration file " + url, e);
        }
    }

    /**
     * 用登记文件的 owner CL 加载实现类；owner 加载不到时回退到本类 CL。
     * （TCCL 与自身 CL 不对称的病态 classpath 下，登记文件与类可见性可能漂移。）
     */
    private Class<?> loadImpl(String fqcn, ClassLoader owner, URL url) {
        try {
            return Class.forName(fqcn, false, owner);
        } catch (ClassNotFoundException e) {
            ClassLoader own = SpiLoader.class.getClassLoader();
            if (own != null && own != owner) {
                try {
                    return Class.forName(fqcn, false, own);
                } catch (ClassNotFoundException ignored) {
                    // 落到下面的诊断
                }
            }
            throw new IllegalStateException("spi impl class not found (登记文件与类路径漂移?): "
                    + fqcn + " (in " + url + ")", e);
        }
    }

    private String joinNames() {
        StringBuilder sb = new StringBuilder();
        for (String name : implClasses.keySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
    }
}

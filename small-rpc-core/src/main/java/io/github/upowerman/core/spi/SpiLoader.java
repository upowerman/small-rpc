package io.github.upowerman.core.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 * 扩展实例懒加载单例；任何异常（缺文件/重名/类型不符）都大声失败，不静默吞。
 */
public final class SpiLoader<S> {

    private static final Logger logger = LoggerFactory.getLogger(SpiLoader.class);

    private static final String PREFIX = "META-INF/small-rpc/";

    private static final ConcurrentHashMap<Class<?>, SpiLoader<?>> LOADERS =
            new ConcurrentHashMap<Class<?>, SpiLoader<?>>();

    private final Class<S> type;
    private final Map<String, Class<S>> implClasses = new LinkedHashMap<String, Class<S>>();
    private final ConcurrentHashMap<String, S> singletons = new ConcurrentHashMap<String, S>();

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

    private S createSingleton(String name) {
        synchronized (this) {
            S instance = singletons.get(name);
            if (instance != null) {
                return instance;
            }
            Class<S> impl = implClasses.get(name);
            if (impl == null) {
                throw new IllegalStateException("no spi extension named '" + name + "' for "
                        + type.getName() + ", supported: " + joinNames());
            }
            instance = newInstance(impl);
            singletons.put(name, instance);
            logger.debug("spi extension instantiated: {} = {}", name, impl.getName());
            return instance;
        }
    }

    private S newInstance(Class<S> impl) {
        try {
            return impl.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate spi extension " + impl.getName()
                    + " (需要公共无参构造器)", e);
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
                        parseFile(url);
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

    private void parseFile(URL url) {
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
                    if (implClasses.containsKey(name)) {
                        throw new IllegalStateException("duplicate spi name '" + name + "' for "
                                + type.getName() + " in " + url);
                    }
                    Class<?> clazz = Class.forName(fqcn, true, SpiLoader.class.getClassLoader());
                    if (!type.isAssignableFrom(clazz)) {
                        throw new IllegalStateException("spi impl " + fqcn + " does not implement "
                                + type.getName() + " (in " + url + ")");
                    }
                    implClasses.put(name, (Class<S>) clazz.asSubclass(type));
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read spi registration file " + url, e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("spi impl class not found (登记文件与类路径漂移?): " + e.getMessage(), e);
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

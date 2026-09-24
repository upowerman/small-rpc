package io.github.upowerman.core.spi;

import io.github.upowerman.core.spi.fixture.DuplicateSameSpi;
import io.github.upowerman.core.spi.fixture.DuplicateSameSpiImpl;
import io.github.upowerman.core.spi.fixture.DuplicateSpi;
import io.github.upowerman.core.spi.fixture.DuplicateSpiImplA;
import io.github.upowerman.core.spi.fixture.DuplicateSpiImplB;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * M6：登记去重口径为 (name, FQCN)。
 * 同一实现经两个 URL 可见（target/classes + 已安装 jar 同时在 classpath）是正常开发场景，
 * 必须静默去重；同名不同实现才是真冲突，仍大声失败。
 * 两个用例各用独立夹具类型：SpiLoader 按类型缓存，失败构造不写缓存。
 */
public class SpiRegistrationDedupTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sameNameDifferentImplFailsLoudly() throws Exception {
        ClassLoader cl = classLoaderWith(DuplicateSpi.class.getName(),
                "dup=" + DuplicateSpiImplA.class.getName(),
                "dup=" + DuplicateSpiImplB.class.getName());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        try {
            SpiLoader.of(DuplicateSpi.class);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("dup"));
            assertTrue(e.getMessage(), e.getMessage().contains(DuplicateSpiImplA.class.getName()));
            assertTrue(e.getMessage(), e.getMessage().contains(DuplicateSpiImplB.class.getName()));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    public void sameImplViaTwoUrlsIsDeduplicatedNotRejected() throws Exception {
        ClassLoader cl = classLoaderWith(DuplicateSameSpi.class.getName(),
                "same=" + DuplicateSameSpiImpl.class.getName(),
                "same=" + DuplicateSameSpiImpl.class.getName());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        try {
            DuplicateSameSpi extension = SpiLoader.of(DuplicateSameSpi.class).getExtension("same");
            assertEquals("same", extension.name());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /** 每个 content 建一个独立 classpath 目录，各自放同一登记文件名，模拟多 URL 可见 */
    private ClassLoader classLoaderWith(String fileName, String... registrationContents) throws Exception {
        List<URL> urls = new ArrayList<URL>();
        for (int i = 0; i < registrationContents.length; i++) {
            File dir = tmp.newFolder("cp" + i);
            File file = new File(dir, "META-INF/small-rpc/" + fileName);
            assertTrue(file.getParentFile().mkdirs());
            Files.write(file.toPath(), registrationContents[i].getBytes(StandardCharsets.UTF_8));
            urls.add(dir.toURI().toURL());
        }
        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }
}

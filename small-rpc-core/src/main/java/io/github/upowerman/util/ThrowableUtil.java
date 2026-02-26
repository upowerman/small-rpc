package io.github.upowerman.util;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @author xuxueli 2018-10-20 20:07:26
 */
public class ThrowableUtil {

    /**
     * parse error to string
     *
     * @param e 异常
     * @return 异常字符串
     */
    public static String toString(Throwable e) {
        StringWriter stringWriter = new StringWriter();
        e.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

}

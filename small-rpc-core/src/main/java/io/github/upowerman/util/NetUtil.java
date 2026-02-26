package io.github.upowerman.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;


/**
 * @author gaoyunfeng
 */
public class NetUtil {


    private static final Logger logger = LoggerFactory.getLogger(NetUtil.class);


    /**
     * check port used
     *
     * @param port 端口
     * @return true: 已被占用; false: 没有被占用
     */
    public static boolean isPortUsed(int port) {
        boolean used = false;
        try (ServerSocket ignored = new ServerSocket(port)) {
        } catch (IOException e) {
            logger.info(">>>>>>>>>>> rpc, port[{}] is in use.", port);
            used = true;
        }
        return used;
    }

}

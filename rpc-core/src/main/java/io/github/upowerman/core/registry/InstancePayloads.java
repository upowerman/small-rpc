package io.github.upowerman.core.registry;

import io.github.upowerman.core.directory.ServiceInstance;

/**
 * 实例元数据（weight / startTime / warmupMillis）与注册中心存储格式的编解码。
 * 格式：{@code w=权重;t=启动时间戳;wm=预热窗口毫秒}，分号分隔的 k=v。
 * <p>
 * 解码是<b>容错</b>的：null / 空 / 乱码 / 缺字段一律回退缺省（weight=1、startTime=0、
 * warmup=0），绝不抛异常——注册数据里的坏行不应让整份快照推送失败（那会触发消费端
 * 缓存冻结在旧状态）。旧版本注册中心写的无元数据条目因此天然兼容（按等权、已预热处理）。
 */
public final class InstancePayloads {

    private static final String KEY_WEIGHT = "w";
    private static final String KEY_START_TIME = "t";
    private static final String KEY_WARMUP = "wm";

    private InstancePayloads() {
    }

    /** 编码实例元数据；全缺省（等权、无预热）时返回空串，注册中心可据此省写 */
    public static String encode(ServiceInstance instance) {
        if (instance == null) {
            return "";
        }
        return KEY_WEIGHT + "=" + instance.getWeight()
                + ";" + KEY_START_TIME + "=" + instance.getStartTime()
                + ";" + KEY_WARMUP + "=" + instance.getWarmupMillis();
    }

    /** 容错解码：坏数据回退缺省实例（等权、未知启动时间=已预热、预热关闭） */
    public static ServiceInstance decode(String address, String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return new ServiceInstance(address);
        }
        int weight = 1;
        long startTime = 0L;
        long warmupMillis = 0L;
        for (String entry : payload.split(";")) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                continue;
            }
            String key = entry.substring(0, eq).trim();
            String value = entry.substring(eq + 1).trim();
            try {
                if (KEY_WEIGHT.equals(key)) {
                    weight = Integer.parseInt(value);
                } else if (KEY_START_TIME.equals(key)) {
                    startTime = Long.parseLong(value);
                } else if (KEY_WARMUP.equals(key)) {
                    warmupMillis = Long.parseLong(value);
                }
            } catch (NumberFormatException ignored) {
                // 坏值按缺省处理
            }
        }
        return new ServiceInstance(address, weight, startTime, warmupMillis);
    }
}

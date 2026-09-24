package io.github.upowerman.core.directory;

import java.util.Collections;
import java.util.List;

/**
 * 直连目录：{@code @RpcReference(address = "host:port")} 场景下跳过注册中心，
 * 恒返回该地址的单元素实例列表。
 * <p>
 * 调用链其余部分零改动：ClusterInvoker 把选中实例的地址写入
 * {@code RpcConstants.ATTACH_ADDRESS}，RemoteInvoker 从 attachments 取它。
 */
public class StaticServiceDirectory implements ServiceDirectory {

    private final String address;

    public StaticServiceDirectory(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("direct address must not be blank: '" + address + "'");
        }
        this.address = address;
    }

    @Override
    public List<ServiceInstance> list(String service) {
        return Collections.singletonList(new ServiceInstance(address));
    }

    /** 直连地址固定，无变更通知 */
    @Override
    public void subscribe(String service) {
    }
}

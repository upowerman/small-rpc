package io.github.upowerman.core.transport;

/**
 * 纯网络层：端点 → 连接
 */
public interface Transport {

    Connection connect(Endpoint endpoint);
}
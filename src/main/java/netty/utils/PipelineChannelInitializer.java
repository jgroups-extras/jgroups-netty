package netty.utils;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.flush.FlushConsolidationHandler;
import netty.listeners.ChannelLifecycleListener;
import netty.listeners.NettyReceiverListener;

/***
 * @author Baizel Mathew
 */
public class PipelineChannelInitializer extends ChannelInitializer<Channel> {
    private final NettyReceiverListener nettyReceiverListener;
    private final ChannelLifecycleListener lifecycleListener;

    public PipelineChannelInitializer(NettyReceiverListener nettyReceiverListener, ChannelLifecycleListener lifecycleListener) {
        this.nettyReceiverListener = nettyReceiverListener;
        this.lifecycleListener = lifecycleListener;
    }

    @Override
    protected void initChannel(Channel ch) {
        ch.pipeline().addFirst(new FlushConsolidationHandler(1000 * 32, true));//outbound and inbound (1)
        ch.pipeline().addLast(new MessageHandler()); // (2)
        ch.pipeline().addLast(new ReceiverHandler(nettyReceiverListener, lifecycleListener)); // (4)
        // inbound ---> 1, 2, 4
        // outbound --> 1
    }
}

package netty.utils;

import java.io.DataInput;
import java.io.InputStream;

import org.jgroups.blocks.cs.netty.NettyConnection;
import org.jgroups.stack.IpAddress;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Attribute;
import netty.listeners.ChannelLifecycleListener;
import netty.listeners.NettyReceiverListener;

/***
 * @author Baizel Mathew
 */
public class ReceiverHandler extends ChannelInboundHandlerAdapter {
    private final NettyReceiverListener nettyReceiverListener;
    private final ChannelLifecycleListener lifecycleListener;

    public ReceiverHandler(NettyReceiverListener nettyReceiverListener, ChannelLifecycleListener lifecycleListener) {
        this.nettyReceiverListener = nettyReceiverListener;
        this.lifecycleListener = lifecycleListener;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        DataInput input = (DataInput) msg;

        IpAddress sender = new IpAddress();
        sender.readFrom(input);

        lifecycleListener.channelRead(ctx.channel(), sender);

        try {
            nettyReceiverListener.onReceive(sender, input);
        } catch (Throwable t) {
            // If there was an error, consume the rest of input
            input.skipBytes(Integer.MAX_VALUE);
            throw t;
        }
        assert !(input instanceof InputStream) || ((InputStream) input).available() == 0;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        lifecycleListener.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        lifecycleListener.channelInactive(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        nettyReceiverListener.onError(cause);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        IpAddress ipAddress = ctx.channel().attr(NettyConnection.ADDRESS_ATTRIBUTE).get();
        Attribute<Boolean> prevWriteStatus = ctx.channel().attr(NettyConnection.ADDRESS_WRITE_STATUS);
        boolean isWriteable = ctx.channel().isWritable();
        if (ipAddress != null && prevWriteStatus != null) {
            if (prevWriteStatus.get() != isWriteable) {
                prevWriteStatus.set(isWriteable);
                nettyReceiverListener.channelWritabilityChanged(ipAddress, isWriteable);
            }
        }
    }
}


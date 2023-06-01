package netty.listeners;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import org.jgroups.stack.IpAddress;

/***
 * @author Baizel Mathew
 */
public interface ChannelLifecycleListener {
    void channelInactive(Channel channel);

    void channelRead(Channel channel, IpAddress sender);

    void channelActive(ChannelHandlerContext ctx);
}

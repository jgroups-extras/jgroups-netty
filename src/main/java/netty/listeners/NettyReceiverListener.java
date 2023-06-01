package netty.listeners;

import java.io.DataInput;

import org.jgroups.Address;
import org.jgroups.PhysicalAddress;

import io.netty.channel.ChannelHandlerContext;

/***
 * @author Baizel Mathew
 */
public interface NettyReceiverListener {
    void onReceive(Address sender, DataInput input) throws Exception;

    void onError(Throwable ex);

    void channelWritabilityChanged(PhysicalAddress outbondAddress, boolean writeable);
}
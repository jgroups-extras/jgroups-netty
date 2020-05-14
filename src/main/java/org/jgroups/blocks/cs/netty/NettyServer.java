package org.jgroups.blocks.cs.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.Errors;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import netty.listeners.ChannelLifecycleListener;
import netty.listeners.NettyReceiverListener;
import netty.utils.PipelineChannelInitializer;
import org.jgroups.Address;
import org.jgroups.stack.IpAddress;

import java.io.*;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/***
 * @author Baizel Mathew
 */
public class NettyServer {

    private int port;
    private InetAddress bind_addr;
    private EventLoopGroup boss_group; // Handles incoming connections
    private EventLoopGroup worker_group;
    private final EventExecutorGroup separateWorkerGroup = new DefaultEventExecutorGroup(4);
    private boolean isNativeTransport;
    private NettyReceiverListener callback;
    private Bootstrap outgoingBootstrap;
    private ChannelLifecycleListener lifecycleListener;
    private byte[] replyAdder = null;
    private Map<IpAddress, Channel> ipAddressChannelMap;

    public NettyServer(InetAddress bind_addr, int port, NettyReceiverListener callback, boolean isNativeTransport) {
        this.port = port;
        this.bind_addr = bind_addr;
        this.callback = callback;
        this.isNativeTransport = isNativeTransport;
        boss_group = isNativeTransport ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
        worker_group = isNativeTransport ? new EpollEventLoopGroup() : new NioEventLoopGroup();
        ipAddressChannelMap = new HashMap<>();

        lifecycleListener = new ChannelLifecycleListener() {
            @Override
            public void channelInactive(Channel channel) {
                ipAddressChannelMap.values().remove(channel);
            }

            @Override
            public void channelRead(Channel channel, IpAddress sender) {
                updateMap(channel, sender);
            }
        };

        outgoingBootstrap = new Bootstrap();
        outgoingBootstrap.group(worker_group)
                .handler(new PipelineChannelInitializer(this.callback, lifecycleListener,separateWorkerGroup))
                .localAddress(bind_addr, 0)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.TCP_NODELAY, true);
        if (isNativeTransport)
            outgoingBootstrap.channel(EpollSocketChannel.class);
        else
            outgoingBootstrap.channel(NioSocketChannel.class);
    }

    public Address getLocalAddress() {
        return new IpAddress(bind_addr, port);
    }

    public void shutdown() throws InterruptedException {
        boss_group.shutdownGracefully();
        worker_group.shutdownGracefully();
        separateWorkerGroup.shutdownGracefully();
    }

    public void run() throws InterruptedException, BindException, Errors.NativeIoException {
        ServerBootstrap inboundBootstrap = new ServerBootstrap();
        inboundBootstrap.group(boss_group, worker_group)
                .localAddress(bind_addr, port)
                .childHandler(new PipelineChannelInitializer(this.callback, lifecycleListener,separateWorkerGroup))
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true);
        if (isNativeTransport) {
            inboundBootstrap.channel(EpollServerSocketChannel.class);
        } else {
            inboundBootstrap.channel(NioServerSocketChannel.class);
        }
        inboundBootstrap.bind().sync();

        try {
            ByteArrayOutputStream replyAddByteStream = new ByteArrayOutputStream();
            DataOutputStream dStream = new DataOutputStream(replyAddByteStream);
            new IpAddress(bind_addr, port).writeTo(dStream);
            replyAdder = replyAddByteStream.toByteArray();
        } catch (IOException e) {
            //Nodes will have to use two channels per connection
            e.printStackTrace();
        }
    }

    public void send(IpAddress destAddr, byte[] data, int offset, int length) {
        Channel opened = ipAddressChannelMap.getOrDefault(destAddr, null);
        if (opened != null) {
            ByteBuf packed = pack(opened.alloc(), data, offset, length, replyAdder);
            writeToChannel(opened, packed);
        } else
            connectAndSend(destAddr, data, offset, length);

    }

    private void writeToChannel(Channel ch, ByteBuf data) {
        ch.eventLoop().execute(() -> {
            ch.writeAndFlush(data, ch.voidPromise());
        });
    }

    public void connectAndSend(IpAddress addr, byte[] data, int offset, int length) {
        ChannelFuture cf = outgoingBootstrap.connect(new InetSocketAddress(addr.getIpAddress(), addr.getPort()));
        // Putting pack(...) inside the lambda causes unexpected behaviour.
        // Both send and receive works fine but it does not get passed up properly, might be something to do with the buffer
        ByteBuf packed = pack(cf.channel().alloc(), data, offset, length, replyAdder);
        cf.addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()) {
                Channel ch = channelFuture.channel();
                writeToChannel(ch, packed);
                updateMap(ch, addr);
            }
        });
    }

    public void connectAndSend(IpAddress addr) {
        //Send an empty message so receiver knows reply addr
        connectAndSend(addr, null, 0, 0);
    }

    private void updateMap(Channel connected, IpAddress destAddr) {
        Channel channel = ipAddressChannelMap.get(destAddr);
        if (channel != null && channel.id() == connected.id())
            return;

        if (channel != null) {
            //if we already have a connection and then this will only be true in one of the nodes thus only closing one connection instead of two
            if (connected.remoteAddress().equals(new InetSocketAddress(destAddr.getIpAddress(), destAddr.getPort()))) {
                connected.close();
            }
            return;
        }
        ipAddressChannelMap.put(destAddr, connected);
    }

    private static ByteBuf pack(ByteBufAllocator allocator, byte[] data, int offset, int length, byte[] replyAdder) {
        int allocSize = Integer.BYTES + Integer.BYTES + length + Integer.BYTES + replyAdder.length;
        ByteBuf buf = allocator.buffer(allocSize);
        buf.writeInt(allocSize - Integer.BYTES);
        buf.writeInt(length);
        buf.writeInt(replyAdder.length);
        buf.writeBytes(replyAdder);
        if (data != null)
            buf.writeBytes(data, offset, length);
        return buf;
    }
}


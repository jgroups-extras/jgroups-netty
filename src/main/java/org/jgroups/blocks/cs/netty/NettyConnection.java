package org.jgroups.blocks.cs.netty;

import static org.jgroups.protocols.TP.MULTICAST;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jgroups.Address;
import org.jgroups.ByteBufMessage;
import org.jgroups.PhysicalAddress;
import org.jgroups.Version;
import org.jgroups.logging.Log;
import org.jgroups.protocols.TP;
import org.jgroups.stack.IpAddress;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.unix.Errors;
import io.netty.util.AttributeKey;
import netty.listeners.ChannelLifecycleListener;
import netty.listeners.NettyReceiverListener;
import netty.utils.PipelineChannelInitializer;

/***
 * @author Baizel Mathew
 */
public class NettyConnection {
    private final Bootstrap clientBootstrap = new Bootstrap();
    private final ServerBootstrap serverBootstrap = new ServerBootstrap();
    private final Map<PhysicalAddress, Channel> clientChannelMap = new ConcurrentHashMap<>();
    private final Map<IpAddress, ChannelFuture> clientFuturesMap = new ConcurrentHashMap<>();
    private final Map<PhysicalAddress, Channel> serverChannelMap = new ConcurrentHashMap<>();
    private final int port;
    private final InetAddress bind_addr;
    private final EventLoopGroup boss_group; // Only handles incoming connections
    private final EventLoopGroup worker_group;
    private final NettyReceiverListener callback;
    private final ChannelLifecycleListener clientLifecycleListener;
    private final ChannelLifecycleListener serverLifecycleListener;
    private final Class<? extends ServerChannel> serverChannel;
    private final Class<? extends SocketChannel> clientChannel;
    private final Log log;
    public byte[] replyAdder = null;


    public NettyConnection(InetAddress bind_addr, int port, NettyReceiverListener callback, Log log,
                           EventLoopGroup bossGroup, EventLoopGroup workerGroup, Class<? extends ServerChannel> serverChannel,
                           Class<? extends SocketChannel> clientChannel) {
        this.port = port;
        this.bind_addr = bind_addr;
        this.callback = callback;
        this.log=log;
        this.boss_group = bossGroup;
        this.worker_group = workerGroup;
        this.serverChannel = Objects.requireNonNull(serverChannel);
        this.clientChannel = Objects.requireNonNull(clientChannel);

        clientLifecycleListener = new ChannelLifecycleListener() {
            @Override
            public void channelInactive(Channel channel) {
                IpAddress ipAddress = channel.attr(ADDRESS_ATTRIBUTE).get();
                if (ipAddress != null) {
                    clientChannelMap.remove(ipAddress);
                } else {
                    clientChannelMap.values().remove(channel);
                }
            }

            @Override
            public void channelRead(Channel channel, IpAddress sender) {
                updateMap(channel, sender, false);
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) { }
        };

        serverLifecycleListener = new ChannelLifecycleListener() {
            @Override
            public void channelInactive(Channel channel) {
                IpAddress ipAddress = channel.attr(ADDRESS_ATTRIBUTE).get();
                if (ipAddress != null) {
                    serverChannelMap.remove(ipAddress);
                } else {
                    serverChannelMap.values().remove(channel);
                }
            }

            @Override
            public void channelRead(Channel channel, IpAddress sender) {
                updateMap(channel, sender, true);
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.channel().attr(ADDRESS_WRITE_STATUS).set(Boolean.TRUE);
            }
        };
        configureServer();
        configureClient();
    }

    public void run() throws InterruptedException, BindException, Errors.NativeIoException {
        serverBootstrap.bind().sync();

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

    public final void send(IpAddress destAddr, boolean oob, ByteBufMessage msg) {
        Channel opened = null;
        if (oob) {
            // OOB messages use the client socket until the server finally gets its connected client
            // By using the server channel for OOB it won't mess with the back pressure of normal messages as they
            // tend to be much higher weight since it can only run sequentially.
            opened = serverChannelMap.get(destAddr);
        }
        if (opened == null) {
            opened = clientChannelMap.get(destAddr);
        }
        if (opened != null) {
            if (opened.eventLoop().inEventLoop()) {
                packAndFlushToChannel(opened, msg);
            } else {
                Channel finalChannel = opened;
                opened.eventLoop().submit(() -> packAndFlushToChannel(finalChannel, msg));
            }
        }
        else
            connectAndSend(destAddr, msg);
    }

    public static AttributeKey<IpAddress> ADDRESS_ATTRIBUTE = AttributeKey.newInstance("jgroups-ipaddress");
    // Unfortunately it is possible for write status to send two writeable messages in a row, this attribute
    // store the last status update we sent and will prevent us from sending duplicate statuses
    public static AttributeKey<Boolean> ADDRESS_WRITE_STATUS = AttributeKey.newInstance("jgroups-write-status");

    public final void connectAndSend(IpAddress addr, ByteBufMessage msg) {
        connectAndSend(addr, ch -> packAndFlushToChannel(ch, msg));
    }

    private void connectAndSend(IpAddress addr, Consumer<Channel> consumer) {
        ChannelFuture cf = openNewClientChannel(addr);
        cf.addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()) {
                Channel ch = channelFuture.channel();
                ch.attr(ADDRESS_ATTRIBUTE).set(addr);
                ch.attr(ADDRESS_WRITE_STATUS).set(Boolean.TRUE);
                consumer.accept(ch);
                updateMap(ch, addr, false);
            } else {
                log.trace("Unable to connect to " + addr, channelFuture.cause());
            }
            clientFuturesMap.remove(addr);
        });
    }
    ChannelFuture openNewClientChannel(IpAddress address) {
        return clientFuturesMap.computeIfAbsent(address, addr -> {
            // Just in case we have a concurrent ChannelFuture complete, note it MUST remove from this map after
            // adding to the map
            Channel addedChannel = clientChannelMap.get(addr);
            if (addedChannel != null) {
                return addedChannel.newSucceededFuture();
            }
            return clientBootstrap.connect(new InetSocketAddress(addr.getIpAddress(), addr.getPort()));
        });
    }

    public Channel getServerChannelForAddress(Address address, boolean server) {
        return (server ? serverChannelMap : clientChannelMap).get(address);
    }

    private void packAndFlushToChannel(Channel ch, ByteBufMessage msg) {
        int bufferSize = (Integer.BYTES * 2) + replyAdder.length + TP.MSG_OVERHEAD + msg.nonPayloadSize();
        ByteBuf first = ch.alloc().buffer(bufferSize, bufferSize);
        ByteBuf payload = msg.getBuf();
        first.writeInt(bufferSize - Integer.BYTES + payload.readableBytes());
        first.writeBytes(replyAdder);

        try (ByteBufOutputStream bbos = new ByteBufOutputStream(first)) {

            byte flags = 0;
            bbos.writeShort(Version.version); // write the version
            if (msg.getDest() == null)
                flags += MULTICAST;
            bbos.writeByte(flags);
            bbos.writeShort(msg.getType());

            msg.writeNonPayload(bbos);
            // TODO: this is written to tell the bytes how big the buffer is
            bbos.writeInt(payload.readableBytes());

            assert first.writerIndex() == first.capacity();
            // We send as two ByteBuf so we don't want to modify the original
            ch.write(first, ch.voidPromise());
            writeAndFlushToChannel(ch, payload);
        } catch (IOException e) {
            // Shouldn't be possible
            throw new RuntimeException(e);
        }
    }

    public final void send(IpAddress destAddr, boolean oob, ByteBuf buf) {
        Channel opened = null;
        if (oob) {
            // OOB messages use the client socket until the server finally gets its connected client
            // By using the server channel for OOB it won't mess with the back pressure of normal messages as they
            // tend to be much higher weight since it can only run sequentially.
            opened = serverChannelMap.get(destAddr);
        }
        if (opened == null) {
            opened = clientChannelMap.get(destAddr);
        }
        if (opened != null) {
            if (opened.eventLoop().inEventLoop()) {
                writeAndFlushToChannel(opened, buf);
            } else {
                Channel finalChannel = opened;
                opened.eventLoop().submit(() -> writeAndFlushToChannel(finalChannel, buf));
            }
        }
        else
            connectAndSend(destAddr, ch -> writeAndFlushToChannel(ch, buf));
    }

    private static void writeAndFlushToChannel(Channel ch, ByteBuf data) {
        ch.writeAndFlush(data, ch.voidPromise());
    }

    private void updateMap(Channel connected, IpAddress destAddr, boolean server) {
        Map<PhysicalAddress, Channel> map = server ? serverChannelMap : clientChannelMap;
        Channel channel = map.get(destAddr);
        if (channel != null) {
            if (channel == connected) {
                return;
            }
        }
        channel = map.putIfAbsent(destAddr, connected);
        if (channel != null) {
            throw new IllegalStateException("Address " + destAddr + " already registered as server: " + server + ", second attempt received!");
        }
        log.debug("%s:%s Destination is server: %s with address %s bound to %s", bind_addr, port, server, destAddr, Thread.currentThread());
        connected.attr(ADDRESS_ATTRIBUTE).set(destAddr);
    }

    private void configureClient() {
        clientBootstrap.group(worker_group)
          .handler(new PipelineChannelInitializer(this.callback, clientLifecycleListener))
          .channel(clientChannel)
          .localAddress(bind_addr, 0)
          .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
          .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.TCP_NODELAY, true);
    }

    private void configureServer() {
        serverBootstrap.group(boss_group, worker_group)
                .localAddress(bind_addr, port)
                .channel(serverChannel)
                .childHandler(new PipelineChannelInitializer(this.callback, serverLifecycleListener))
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
          .childOption(ChannelOption.TCP_NODELAY, true);
    }

    private static ByteBuf pack(ByteBufAllocator allocator, byte[] data, int offset, int length, byte[] replyAdder) {
        // TODO: we can optimize away the replyAddr later
        int allocSize = Integer.BYTES + length + replyAdder.length;
        ByteBuf buf = allocator.buffer(allocSize, allocSize);
        // size of data + size replyAddr.length field  + space for reply addr bytes = total frame size
        buf.writeInt(length + replyAdder.length);  //encode frame size and data length
        buf.writeBytes(replyAdder);
        if (data != null)
            buf.writeBytes(data, offset, length);
        assert buf.writerIndex() == buf.capacity();
        return buf;
    }
}


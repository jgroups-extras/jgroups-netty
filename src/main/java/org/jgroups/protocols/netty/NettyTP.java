package org.jgroups.protocols.netty;

import java.io.DataInput;
import java.io.IOException;
import java.net.BindException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.jgroups.Address;
import org.jgroups.ByteBufMessage;
import org.jgroups.Event;
import org.jgroups.Message;
import org.jgroups.PhysicalAddress;
import org.jgroups.Version;
import org.jgroups.annotations.Property;
import org.jgroups.blocks.cs.netty.NettyConnection;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.protocols.NoBundler;
import org.jgroups.protocols.TP;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.MemberAvailabilityEvent;
import org.jgroups.util.MessageCompleteEvent;
import org.jgroups.util.NettyAsyncHeader;
import org.jgroups.util.NonBlockingPassRegularMessagesUpDirectly;
import org.jgroups.util.Util;
import org.jgroups.util.WatermarkOverflowEvent;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.Errors;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringServerSocketChannel;
import io.netty.incubator.channel.uring.IOUringSocketChannel;
import netty.listeners.NettyReceiverListener;

/***
 * @author Baizel Mathew
 */
public class NettyTP extends TP implements NettyReceiverListener {
    @Property(description = "Use Native packages when available")
    protected boolean use_native_transport;

    @Property(description = "Uses the Netty incubator (https://github.com/netty/netty-incubator-transport-io_uring) to " +
          "use IO_URING. Requires Linux with a kernel >= 5.9")
    protected boolean use_io_uring;

    private NettyConnection server;
    private IpAddress selfAddress;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private Class<? extends ServerChannel> serverChannel;
    private Class<? extends SocketChannel> clientChannel;

    private boolean initCalledPrior;

    public NettyTP() {
        msg_processing_policy = new NonBlockingPassRegularMessagesUpDirectly();
        bundler_type = "no-bundler";
    }

    public NettyConnection getServer() {
        return server;
    }

    @Override
    public void init() throws Exception {
        ClassConfigurator.addIfAbsent(NettyAsyncHeader.MAGIC_ID, NettyAsyncHeader.class);

        super.init();
        if (serverChannel == null) {
            serverChannel = serverChannel();
        }
        if (clientChannel == null) {
            clientChannel = clientChannel();
        }
        if (!(msg_processing_policy instanceof NonBlockingPassRegularMessagesUpDirectly)) {
            log.debug("msg_processing_policy was set, ignoring as NettyTP requires it specific policy");
            msg_processing_policy = new NonBlockingPassRegularMessagesUpDirectly();
            msg_processing_policy.init(this);
        }

        if (!(bundler instanceof NoBundler)) {
            log.debug("bundler was set, ignoring as NettyTP requires NoBundler");
            bundler = new NoBundler();
            bundler.init(this);
        }

        if (!initCalledPrior) {
            msg_factory.register(ByteBufMessage.BYTE_BUF_MSG, () -> new ByteBufMessage(ByteBufAllocator.DEFAULT));
            initCalledPrior = true;
        }
    }

    public void replaceBossEventLoop(EventLoopGroup bossGroup) {
        if (server != null) {
            throw new IllegalStateException("Boss event loop group cannot be set after server has been started!");
        }
        if (this.bossGroup != null) {
            this.bossGroup.shutdownGracefully();
        }
        this.bossGroup = bossGroup;
    }

    public void replaceWorkerEventLoop(EventLoopGroup workerGroup) {
        if (server != null) {
            throw new IllegalStateException("Worker event loop group cannot be set after server has been started!");
        }
        if (this.workerGroup != null) {
            this.workerGroup.shutdownGracefully();
        }
        this.workerGroup = workerGroup;
    }

    public void setServerChannel(Class<? extends ServerChannel> serverChannel) {
        if (server != null) {
            throw new IllegalStateException("Server channel cannot be set after server has been started!");
        }
        this.serverChannel = serverChannel;
    }

    public void setSocketChannel(Class<? extends SocketChannel> clientChannel) {
        if (server != null) {
            throw new IllegalStateException("Client channel cannot be set after server has been started!");
        }
        this.clientChannel = clientChannel;
    }

    @Override
    public boolean supportsMulticasting() {
        return false;
    }

    @Override
    public void sendUnicast(PhysicalAddress dest, byte[] data, int offset, int length) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getInfo() {
        return null;
    }

    @Override
    public void start() throws Exception {
        super.start();
        initializeNettyGroupsIfNecessary();
        // We have to set this before actually being connected, because it is possible the server/client may
        // get a request before we assign the server reference
        selfAddress = new IpAddress(bind_addr, bind_port);
        boolean isServerCreated = createServer();
        while (!isServerCreated && bind_port < bind_port + port_range) {
            //Keep trying to create server until
            bind_port++;
            selfAddress = new IpAddress(bind_addr, bind_port);
            isServerCreated = createServer();
            //TODO: Fix this to get valid port numbers
        }
        if (!isServerCreated) {
            selfAddress = null;
            throw new BindException("No port found to bind within port range");
        }
    }

    private void initializeNettyGroupsIfNecessary() {
        if (bossGroup == null) {
            bossGroup = createEventLoopGroup(1);
        }
        if (workerGroup == null) {
            workerGroup = createEventLoopGroup(0);
        }
    }

    protected EventLoopGroup createEventLoopGroup(int numThreads) {
        if(use_io_uring) {
            try {
                return numThreads > 0? new IOUringEventLoopGroup(numThreads) : new IOUringEventLoopGroup();
            }
            catch(Throwable t) {
                log.warn("failed to use io_uring (disabling it): " + t.getMessage());
                use_io_uring=false;
            }
        }
        if(use_native_transport) {
            try {
//                if(Util.checkForMac())
//                    return numThreads > 0? new KQueueEventLoopGroup(numThreads) : new KQueueEventLoopGroup();
                return numThreads > 0? new EpollEventLoopGroup(numThreads) : new EpollEventLoopGroup(); // Linux
            }
            catch(Throwable t) {
                log.warn("failed to use native transport", t);
                use_native_transport=false;
            }
        }
        log.debug("falling back to " + NioEventLoopGroup.class.getSimpleName());
        return numThreads > 0? new NioEventLoopGroup(numThreads) : new NioEventLoopGroup();
    }

    @Override
    public void stop() {
        // Shut down without a quiet period
        bossGroup.shutdownGracefully(0, 10, TimeUnit.SECONDS);
        workerGroup.shutdownGracefully(0, 10, TimeUnit.SECONDS);
        super.stop();
    }

    // Most of this method is copied from TP#receive
    @Override
    public void onReceive(Address sender, DataInput in) throws Exception {
        receive(sender, in);
    }

    public PhysicalAddress toPhysicalAddress(Address address) {
        if (address instanceof PhysicalAddress) {
            return (PhysicalAddress) address;
        }
        return (PhysicalAddress) down(new Event(Event.GET_PHYSICAL_ADDRESS, address));
    }

    @Override
    public Object down(Event evt) {
        Object retVal = super.down(evt);
        switch (evt.getType()) {
            case Event.TMP_VIEW:
            case Event.VIEW_CHANGE:
                ((NonBlockingPassRegularMessagesUpDirectly)msg_processing_policy).viewChange(view.getMembers());
                break;
            case Event.USER_DEFINED:
                if (evt instanceof MessageCompleteEvent) {
                    Message msg = evt.getArg();
                    ((NonBlockingPassRegularMessagesUpDirectly) msg_processing_policy).completedMessage(msg);
                } else if (evt instanceof WatermarkOverflowEvent) {
                    Address address = ((WatermarkOverflowEvent) evt).address();
                    Address physicalAddress = toPhysicalAddress(address);

                    Channel channel = server.getServerChannelForAddress(physicalAddress, true);
                    channel.config().setAutoRead(!((WatermarkOverflowEvent) evt).wasOverFlow());
                }
                break;
        }
        return retVal;
    }

    @Override
    public void channelWritabilityChanged(PhysicalAddress outboundAddress, boolean writeable) {
        if (is_trace) {
            if (writeable) {
                log.trace("%s Member %s is available for writing, sending event up to notify user to continue", addr(), outboundAddress);
            } else {
                log.trace("%s Member %s is no longer available for writing, sending event up to notify user to reduce pressure", addr(), outboundAddress);
            }
        }
        Address logicalAddress = logical_addr_cache.getByValue(outboundAddress);
        up(new MemberAvailabilityEvent(logicalAddress, writeable));
    }

    @Override
    public void onError(Throwable ex) {
        log.error("error received at Netty transport ", ex);
    }

    @Override
    protected PhysicalAddress getPhysicalAddress() {
        return selfAddress;
    }

    @Override
    protected void _send(Message msg, Address dest) {
        if(stats) {
            msg_stats.incrNumMsgsSent(1);
            msg_stats.incrNumBytesSent(msg.size());
        }
        // Note this completely bypasses the bundler
        ByteBuf messageBytes = null;
        if (dest == null) {
            // Not we send the original message first and then copy afterwards - this is safe because refCnt is 2
            for (Address mbr : members) {
                PhysicalAddress target = mbr instanceof PhysicalAddress ? (PhysicalAddress) mbr : logical_addr_cache.get(mbr);
                if (Objects.equals(local_physical_addr, target)) {
                    continue;
                }
                ByteBuf bufToUse;
                if (messageBytes != null) {
                    // Share memory region between commands but retain the reference so it won't be released
                    // until all are done reading it
                    bufToUse = messageBytes.retainedSlice(0, messageBytes.writerIndex());
                } else {
                    // Need to retain so next loop can reference the buffer in case it was consumed already
                    bufToUse = messageBytes = bufFromMessage(msg, dest).retain();
                }
                try {
                    bufSend(bufToUse, msg.isFlagSet(Message.Flag.OOB), target);
                } catch (Throwable t) {
                    log.error(Util.getMessage("FailureSendingToPhysAddr"), local_addr, mbr, t);
                }
            }
            if (messageBytes != null) {
                messageBytes.release();
            }
        } else {
            bufSend(bufFromMessage(msg, dest), msg.isFlagSet(Message.Flag.OOB), dest);
        }
    }

    private ByteBuf bufFromMessage(Message msg, Address dest) {
        byte[] replyAdder = server.replyAdder;
        if (msg instanceof ByteBufMessage) {
            return bufFromMessage(replyAdder, (ByteBufMessage) msg, dest);
        }

        int totalSize = msg.size() + TP.MSG_OVERHEAD + replyAdder.length;
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(totalSize + Integer.BYTES, totalSize + Integer.BYTES);
        buf.writeInt(totalSize);
        buf.writeBytes(replyAdder);
        try (ByteBufOutputStream bbos = new ByteBufOutputStream(buf)) {
            Util.writeMessage(msg, bbos, msg.dest() == null);
        } catch(IOException e) {
            log.trace(Util.getMessage("SendFailure"), local_addr, (dest == null? "cluster" : dest), msg.size(),
                  e.toString(), msg.printHeaders());
        }
        return buf;
    }

        private ByteBuf bufFromMessage(byte[] replyAdder, ByteBufMessage msg, Address dest) {
            int bufferSize = (Integer.BYTES * 2) + replyAdder.length + TP.MSG_OVERHEAD + msg.nonPayloadSize();
            ByteBuf first = ByteBufAllocator.DEFAULT.buffer(bufferSize, bufferSize);
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
            } catch (IOException e) {
                log.trace(Util.getMessage("SendFailure"), local_addr, (dest == null? "cluster" : dest), msg.size(),
                      e.toString(), msg.printHeaders());
            }
            return Unpooled.compositeBuffer(2).addComponent(true, first)
                  .addComponent(true, payload);
        }

    private void byteBufSend(ByteBufMessage msg, Address dest) {
        server.send((IpAddress) toPhysicalAddress(dest), msg.isFlagSet(Message.Flag.OOB), msg);
    }

    private void bufSend(ByteBuf buf, boolean oob, Address dest) {
        server.send((IpAddress) toPhysicalAddress(dest), oob, buf);
    }

    private boolean createServer() {
        try {
            server = new NettyConnection(bind_addr, bind_port, this, log, bossGroup, workerGroup,
                  serverChannel, clientChannel);
            server.run();
        } catch (BindException | Errors.NativeIoException | InterruptedException exception) {
            return false;
        }
        return true;
    }
    protected Class<? extends ServerChannel> serverChannel() {
        if(use_io_uring)
            return IOUringServerSocketChannel.class;
        if(use_native_transport) {
            if(Util.checkForMac())
                return KQueueServerSocketChannel.class;
            return EpollServerSocketChannel.class;
        }
        return NioServerSocketChannel.class;
    }

    protected Class<? extends SocketChannel> clientChannel() {
        if(use_io_uring)
            return IOUringSocketChannel.class;
        if(use_native_transport) {
            if(Util.checkForMac())
                return KQueueSocketChannel.class;
            return EpollSocketChannel.class;
        }
        return NioSocketChannel.class;
    }
}

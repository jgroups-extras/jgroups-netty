package org.jgroups.util;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.stream.Collectors;

import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.PhysicalAddress;
import org.jgroups.Refcountable;
import org.jgroups.annotations.ManagedOperation;
import org.jgroups.annotations.Property;
import org.jgroups.protocols.MsgStats;
import org.jgroups.protocols.TP;
import org.jgroups.protocols.TpHeader;
import org.jgroups.protocols.netty.NettyTP;

import io.netty.channel.EventLoop;

/**
 * This class is very similar to {@link MaxOneThreadPerSender} and in fact many of the code is copied from there.
 * The big difference is that it will never block the invoking thread.
 * The general idea is that non oob messages will be processed in order, as usual, but we rely upon external notifications
 * to tell us that the message was completed via
 */
public class NonBlockingPassRegularMessagesUpDirectly extends SubmitToThreadPool {
   protected final ConcurrentMap<Address, Entry> senderTable = new ConcurrentHashMap<>();

   public void viewChange(List<Address> members) {
      senderTable.keySet().retainAll(members);
   }

   public void destroy() {
      senderTable.clear();
   }

   @Override
   public void reset() {
      senderTable.values().forEach(Entry::reset);
   }

   protected NettyTP transport;

   public void init(NettyTP transport) {
      super.init(transport);
      if (low_watermark <= 0) {
         log.debug("msg_processing_policy.low_watermark was set 0 or less, reverting to default of " + DEFAULT_LOW_WATER_MARK);
         low_watermark = DEFAULT_LOW_WATER_MARK;
      }
      if (high_watermark <= 0) {
         log.debug("msg_processing_policy.high_watermark was set 0 or less, reverting to default of " + DEFAULT_HIGH_WATER_MARK);
         high_watermark = DEFAULT_HIGH_WATER_MARK;
      }
      this.transport = transport;
   }

   @Override
   public void init(TP transport) {
      if (!(transport instanceof NettyTP)) {
         throw new UnsupportedOperationException("Only support NettyTP transports!");
      }
      init((NettyTP) transport);
   }

   private static final int DEFAULT_LOW_WATER_MARK = 32 * 1024;
   private static final int DEFAULT_HIGH_WATER_MARK = 64 * 1024;

   @Property(description="When pending non oob messages from sender are reduced below this after previous exceeding high_watermark will allow reads to become unblocked." +
         " Must be greater than 0, defaults to " + DEFAULT_LOW_WATER_MARK)
   protected int                low_watermark = DEFAULT_LOW_WATER_MARK;
   @Property(description="When pending non oob messages from sender exceed this amount, additional reads will be stopped until byte total is less than low_watermark." +
         " Must be greater than 0, defaults to " + DEFAULT_HIGH_WATER_MARK)
   protected int                high_watermark = DEFAULT_HIGH_WATER_MARK;

   @ManagedOperation(description="Dumps unicast and multicast tables")
   public String dump() {
      return String.format("\nsenderTable:\n%s", mapToString(senderTable));
   }

   static String mapToString(ConcurrentMap<Address, Entry> map) {
      return map.values().stream()
            .map(Object::toString)
            .collect(Collectors.joining("\n", "", ""));
   }

   public void completedMessage(Message msg) {
      Entry entry = senderTable.get(msg.getSrc());
      if (entry != null) {
         if (tp.isTrace()) {
            log.trace("%s Marking %s as completed", tp.addr(), msg);
         }
         entry.messageCompleted(msg);
      } else {
         log.debug("%s Message %s was marked as completed, but was not present in MessageTable, most likely concurrent stop", tp.addr(), msg);
      }
   }

   @Override
   public boolean loopback(Message msg, boolean oob) {
      if(oob)
         return super.loopback(msg, oob);
      // Rely upon event loop just running the command
      tp.passMessageUp(msg, null, false, msg.getDest() == null, false);
      return true;
   }

   @Override
   public boolean process(MessageBatch batch, boolean oob) {
      if (oob) {
         return super.process(batch, true);
      }
      Entry entry = senderTable.computeIfAbsent(batch.sender(), Entry::new);
      return entry.process(batch);
   }

   @Override
   public boolean process(Message msg, boolean oob) {
      if (oob) {
         return tp.getThreadPool().execute(new CloseSingleMessageHandler(msg));
      }
      Entry entry = senderTable.computeIfAbsent(msg.getSrc(), Entry::new);
      return entry.process(msg);
   }

   class CloseSingleMessageHandler extends SingleMessageHandler {
      protected CloseSingleMessageHandler(Message msg) {
         super(msg);
      }

      @Override
      public void run() {
         try {
            super.run();
         } finally {
            if (msg instanceof Refcountable) {
               ((Refcountable<?>) msg).decr();
            }
         }
      }
   }

   private static final AtomicLongFieldUpdater<Entry> SUBMITTED_MSGS_UPDATER = AtomicLongFieldUpdater.newUpdater(Entry.class, "submitted_msgs");
   private static final AtomicLongFieldUpdater<Entry> QUEUED_MSGS_UPDATER = AtomicLongFieldUpdater.newUpdater(Entry.class, "queued_msgs");

   protected class Entry implements Runnable {
      volatile boolean running = false;
      // This variable is only accessed from the event loop tied with the sender
      protected final ArrayDeque<Message> batch;    // used to queue messages
      protected final Thread ourThread;
      protected final EventLoop ourEventLoop;
      protected final Address      sender;

      protected volatile long               submitted_msgs;
      protected volatile long               queued_msgs;

      // Needs to be volatile as we can read it from a different thread on completion
      protected volatile Message   messageBeingProcessed;
      protected long batchLength;
      protected boolean sentOverFlow;

      protected Entry(Address sender) {
         this.sender=sender;
         batch=new ArrayDeque<>();
         ourThread=Thread.currentThread();

         PhysicalAddress physicalAddress = transport.toPhysicalAddress(sender);

         this.ourEventLoop = transport.getServer().getServerChannelForAddress(physicalAddress, true).eventLoop();
         if (!ourEventLoop.inEventLoop(ourThread)) {
            throw new IllegalStateException("Event loop " + ourEventLoop + " doesn't match invoking thread " + ourThread);
         }
         if (tp.isTrace()) {
            log.trace("%s Creating inbound entry handler for %s", tp.addr(), physicalAddress);
         }
      }


      public Entry reset() {
         submitted_msgs=queued_msgs=0;
         return this;
      }

      protected void messageCompleted(Message msg) {
         if (msg != messageBeingProcessed) {
            log.error("%s Inconsistent message completed %s versus processing %s, this is most likely a bug!", tp.addr(), msg, messageBeingProcessed);
         }
         if (msg instanceof Refcountable) {
            ((Refcountable<?>) msg).decr();
         }
         if (ourEventLoop.inEventLoop()) {
            if (running) {
               if (tp.isTrace()) {
                  log.trace("%s Message %s completed synchronously for sender %s", tp.addr(), msg, sender);
               }
               messageBeingProcessed = null;
               return;
            }
         }
         if (tp.isTrace()) {
            log.trace("%s Message %s completed async, dispatching next message if applicable for sender %s on thread %s",
                  tp.addr(), msg, sender, ourThread);
         }

         // NOTE: we cannot set messageBeingProcessed to null as it was completed asynchronously, because if there is a
         // pending read between our submission below that it enqueues the message

         if (ourEventLoop.inEventLoop()) {
            run();
         } else {
            ourEventLoop.execute(this);
         }
      }

      /**
       * Submits the single message and returns whether the message was processed synchronously or not
       * @param msg the message to send up
       * @return whether the message completed synchronously
       */
      protected boolean submitMessage(Message msg) {
         assert ourEventLoop.inEventLoop();
         running = true;
         // Following block is just copied from SubmitToThreadPool#SingleMessageHandler instead of allocating a new
         // object and also because the constructor is protected
         {
            Address dest=msg.getDest();
            boolean multicast=dest == null;
            try {
               if(tp.statsEnabled()) {
                  MsgStats msg_stats=tp.getMessageStats();
                  boolean oob=msg.isFlagSet(Message.Flag.OOB);
                  if(oob)
                     msg_stats.incrNumOOBMsgsReceived(1);
                  else
                     msg_stats.incrNumMsgsReceived(1);
                  msg_stats.incrNumBytesReceived(msg.getLength());
               }
               TpHeader hdr=msg.getHeader(tp_id);
               byte[] cname = hdr.getClusterName();
               tp.passMessageUp(msg, cname, true, multicast, true);
            }
            catch(Throwable t) {
               log.error(Util.getMessage("PassUpFailure"), t);
            }

         }
         running = false;
         // Check for the presence of the async header to tell if message may be delayed
         if (!(msg.getHeader(tp.getId()) instanceof NettyAsyncHeader)) {
            if (tp.isTrace()) {
               log.trace("%s Message %s assumed to complete synchronously as no header was present", tp.addr(), msg);
            }
            messageBeingProcessed = null;
            if (msg instanceof Refcountable) {
               ((Refcountable<?>) msg).decr();
            }
         } else if (messageBeingProcessed != null) {
            if (tp.isTrace()) {
               log.trace("%s Message %s not completed synchronously, must wait until it is complete later", tp.addr(), msg);
            }
            return false;
         }
         return true;
      }

      public boolean process(Message msg) {
         assert ourEventLoop.inEventLoop();
         if (messageBeingProcessed != null) {
            QUEUED_MSGS_UPDATER.incrementAndGet(this);
            batch.add(msg);
            notifyOnWatermarkOverflow(msg.getSrc());
            return false;
         }
         SUBMITTED_MSGS_UPDATER.incrementAndGet(this);
         messageBeingProcessed = msg;
         return submitMessage(msg);
      }

      public boolean process(MessageBatch batch) {
         assert ourEventLoop.inEventLoop();
         if (messageBeingProcessed != null) {
            QUEUED_MSGS_UPDATER.addAndGet(this, batch.size());
            batch.forEach(this.batch::add);
            notifyOnWatermarkOverflow(batch.sender());
            return false;
         }
         int submittedAmount = 0;
         Iterator<Message> iter = batch.iterator();
         while (iter.hasNext()) {
            Message msg = iter.next();

            submittedAmount++;
            messageBeingProcessed = msg;
            if (!submitMessage(msg)) {
               break;
            }
         }
         SUBMITTED_MSGS_UPDATER.addAndGet(this, submittedAmount);
         int queuedAmount = 0;
         while (iter.hasNext()) {
            Message msg = iter.next();
            queuedAmount++;
            batch.add(msg);
         }
         QUEUED_MSGS_UPDATER.addAndGet(this, queuedAmount);
         notifyOnWatermarkOverflow(batch.sender());
         return false;
      }

      private void notifyOnWatermarkOverflow(Address sender) {
         if (batchLength < high_watermark) {
            long newBatchLength = batchLength();
            if (tp.isTrace()) {
               log.trace("%s Batch size has increased from %s to %s bytes with %d messages for sender %s", tp.addr(),
                     batchLength, newBatchLength, batch.size(), sender);
            }
            batchLength = newBatchLength;
            if (batchLength > high_watermark) {
               if (tp.isTrace()) {
                  log.trace("%s High watermark met for sender %s, pausing reads", tp.addr(), sender);
               }
               tp.down(new WatermarkOverflowEvent(sender, true));
               sentOverFlow = true;
            }
         } else if (tp.isTrace()) {
            log.trace("%s Batch size has increased to %d messages for sender %s", tp.addr(), batch.size(), sender);
         }
      }

      // unsynchronized on batch but who cares
      public String toString() {
         return String.format("batch size=%d queued msgs=%d submitted msgs=%d",
               batch.size(), queued_msgs, submitted_msgs);
      }

      protected long batchLength() {
         long size = 0;
         for (Message message : batch) {
            size += message.getLength();
         }
         return size;
      }

      // This code can only be invoked in the event loop for this sender
      @Override
      public void run() {
         assert ourEventLoop.inEventLoop();
         assert messageBeingProcessed != null;

         boolean trace = tp.isTrace();

         messageBeingProcessed = null;
         if (batch.isEmpty()) {
            if (trace) {
               log.trace("%s Batch is exhausted for sender %s", tp.addr(), sender);
            }
            return;
         }

         if (trace) {
            log.trace("%s Batch has %d messages remaining", tp.addr(), batch.size());
         }

         int processedAmount = 0;
         Message msg;
         while ((msg = batch.pollFirst()) != null) {
            messageBeingProcessed = msg;
            if (!submitMessage(msg)) {
               break;
            }
            processedAmount++;
         }
         long endingLength = batchLength();
         batchLength = endingLength;
         if (trace) {
            log.trace("%s Processed %d messages for %s, new batch size is %d", tp.addr(), processedAmount, sender, endingLength);
         }
         if (sentOverFlow && endingLength < low_watermark) {
            if (trace) {
               log.trace("%s Low watermark met for %s, resuming reads", tp.addr(), sender);
            }
            tp.down(new WatermarkOverflowEvent(sender, false));
            sentOverFlow = false;
         }
      }
   }
}

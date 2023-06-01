package org.jgroups.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.function.Supplier;

import org.jgroups.Header;

/**
 * Header for external users to use to mark that this message may complete asynchronously and should wait for
 * a {@link MessageCompleteEvent} with that message as the argument to notify jgroups the message has been completed
 */
public class NettyAsyncHeader extends Header {
   public static final short MAGIC_ID = 1050;
   @Override
   public short getMagicId() {
      return MAGIC_ID;
   }

   @Override
   public Supplier<? extends Header> create() {
      return NettyAsyncHeader::new;
   }

   @Override
   public int serializedSize() {
      return 0;
   }

   @Override
   public void writeTo(DataOutput out) throws IOException {

   }

   @Override
   public void readFrom(DataInput in) throws IOException, ClassNotFoundException {

   }
}

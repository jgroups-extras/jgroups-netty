package org.jgroups;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import org.jgroups.util.ByteArray;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.ByteBufferInputStream;
import org.jgroups.util.Util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import netty.utils.ExposedByteBufInputStream;

public class ByteBufMessage extends BaseMessage implements Refcountable<ByteBufMessage> {
   static public final short BYTE_BUF_MSG        = 1234;

   private final ByteBufAllocator allocator;
   private ByteBuf buf;
   private byte[] array;

   public ByteBufMessage(ByteBufAllocator allocator, ByteBuf buf) {
      this(allocator);
      this.buf = buf;
   }

   public ByteBufMessage(ByteBufAllocator allocator) {
      this.allocator = allocator;
   }

   public ByteBuf getBuf() {
      return buf;
   }

   @Override
   public short getType() {
      return BYTE_BUF_MSG;
   }

   @Override
   public boolean hasPayload() {
      return buf != null;
   }

   @Override
   public boolean hasArray() {
      return buf != null;
   }

   @Override
   public byte[] getArray() {
      if (array == null && buf != null) {
         array = new byte[buf.readableBytes()];
         buf.getBytes(buf.readerIndex(), array, 0, buf.readableBytes());
      }
      return array;
   }

   @Override
   public int getOffset() {
      return 0;
   }

   @Override
   public int getLength() {
      return buf != null ? buf.readableBytes() : 0;
   }

   @Override
   public Message setArray(byte[] b, int offset, int length) {
      throw new UnsupportedOperationException();
   }

   @Override
   public Message setArray(ByteArray buf) {
      throw new UnsupportedOperationException();
   }

   @Override
   public <T> T getObject() {
      throw new UnsupportedOperationException();
   }

   @Override
   public Message setObject(Object obj) {
      throw new UnsupportedOperationException();
   }

   @Override
   public void writePayload(DataOutput out) throws IOException {
      out.writeInt(buf.readableBytes());
      buf.forEachByte(b -> {
         out.writeByte(b);
         return true;
      });
   }

   @Override
   public void readPayload(DataInput in) throws IOException {
      int length = in.readInt();
      if (length <= 0) {
         return;
      }
      if (in instanceof ExposedByteBufInputStream) {
         ByteBuf buffer = ((ExposedByteBufInputStream) in).getBuf();
         int endOffset = ((ExposedByteBufInputStream) in).getEndReadIndex();
         int readIndex = buffer.readerIndex();
         assert length == endOffset - readIndex;
         buf = buffer.retainedSlice(readIndex, length);
         // Advance buffer
         buffer.readerIndex(readIndex + length);
      } else if (in instanceof ByteArrayDataInputStream) {
         buf = fromByteArrayDataInputStream((ByteArrayDataInputStream) in, length);
      } else if (in instanceof ByteBufferInputStream) {
         buf = fromByteBufferInputStream((ByteBufferInputStream) in, length);
      } else {
         buf = allocator.buffer(length, length);
         if (in instanceof InputStream) {
            buf.writeBytes((InputStream) in, length);
         } else {
            for (int i = 0; i < length; ++i) {
               buf.writeByte(in.readByte());
            }
         }
      }
   }

   @Override
   public int size() {
      return super.size() + sizeOfPayload();
   }

   protected int sizeOfPayload() {
      int retval=Global.INT_SIZE; // length
      buf.readableBytes();         // number of bytes in the array
      return retval;
   }

   public int nonPayloadSize() {
      return super.size();
   }

   public void writeNonPayload(DataOutput out) throws IOException {
      byte leading=0;

      if(dest != null)
         leading= Util.setFlag(leading, DEST_SET);

      if(sender != null)
         leading=Util.setFlag(leading, SRC_SET);

      // write the leading byte first
      out.writeByte(leading);

      // write the flags (e.g. OOB, LOW_PRIO), skip the transient flags
      out.writeShort(flags);

      // write the dest_addr
      if(dest != null)
         Util.writeAddress(dest, out);

      // write the src_addr
      if(sender != null)
         Util.writeAddress(sender, out);

      // write the headers
      writeHeaders(this.headers, out, (short[])null);
   }

   private ByteBuf fromByteArrayDataInputStream(ByteArrayDataInputStream in, int length) {
      int pos = in.position();
      int limit = in.limit();
      int readAmount = Math.min(limit - pos, length);
      in.advance(readAmount);
      return Unpooled.wrappedBuffer(in.buffer(), pos, readAmount);
   }

   private ByteBuf fromByteBufferInputStream(ByteBufferInputStream in, int length) {
      ByteBuffer buffer = in.buf();
      ByteBuf buf = Unpooled.wrappedBuffer(buffer.duplicate());
      buffer.position(buffer.position() + length);
      // We have to limit the read to only our designated length
      int readerIndex = buf.readerIndex();
      buf.writerIndex(readerIndex + length);
      return buf;
   }

   @Override
   public Supplier<? extends Message> create() {
      return () -> new ByteBufMessage(allocator);
   }

   @Override
   protected Message copyPayload(Message copy) {
      assert ((ByteBufMessage) copy).buf == null;
      ((ByteBufMessage) copy).buf = buf.retainedSlice();
      return super.copyPayload(copy);
   }

   @Override
   public ByteBufMessage incr() {
      buf.retain();
      return this;
   }

   @Override
   public ByteBufMessage decr() {
      buf.release();
      return this;
   }
}

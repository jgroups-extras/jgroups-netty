package netty.utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

public class ExposedByteBufInputStream extends ByteBufInputStream {
   private final ByteBuf buf;
   private final int endReadIndex;
   public ExposedByteBufInputStream(ByteBuf buffer, int length) {
      super(buffer, length);
      this.buf = buffer;
      this.endReadIndex = buffer.readerIndex() + length;
   }

   public ByteBuf getBuf() {
      return buf;
   }

   public int getEndReadIndex() {
      return endReadIndex;
   }
}

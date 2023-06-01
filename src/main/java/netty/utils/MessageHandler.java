package netty.utils;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

public class MessageHandler extends ByteToMessageDecoder {
   @Override
   protected void decode(ChannelHandlerContext ctx, ByteBuf msgbuf, List<Object> out) {
      if (msgbuf.readableBytes() < 4) {
         return;
      }
      int startingPos = msgbuf.readerIndex();
      int totalLength = msgbuf.readInt();
      if (msgbuf.readableBytes() < totalLength) {
         msgbuf.readerIndex(startingPos);
         return;
      }
      out.add(new ExposedByteBufInputStream(msgbuf, totalLength));
   }
}

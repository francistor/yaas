package diameterServer

import akka.util.{ByteStringBuilder, ByteString}

object UByteString{
  
  def putUnsigned24(bsb: ByteStringBuilder, value: Int): Unit = {
    val byte0 = value % 256
    val byte1 = (value >> 8) % 256
    val byte2 = value / 65536
    
    bsb.putByte(byte2.toByte).putByte(byte1.toByte).putByte(byte0.toByte)
  }
  
  def getUnsigned24(bs: ByteString) : Int = {
    val byte2 = bs.slice(0, 1).toByteBuffer.get
    val short = bs.slice(1, 3).toByteBuffer.getShort
    val lower = if(short > 0) short else (65536 + short)
    
    65536 * byte2 + lower
  }
}
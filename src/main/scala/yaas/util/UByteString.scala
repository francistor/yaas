package yaas.util

import akka.util.{ByteStringBuilder, ByteString, ByteIterator}

/**
 * Helpers for 24 bit numeric values
 */
object UByteString {
  
  implicit val byteOrder = java.nio.ByteOrder.BIG_ENDIAN
  
  def toUnsignedByte(value: Int) : Byte = {
    if(value >= 128) (value - 256).toByte else value.toByte
  }
  
  def toUnsignedShort(value: Int) : Short = {
    if(value >= 32768 ) (value - 65536).toShort else value.toShort
  }
  
  def fromUnsignedByte(value: Byte) : Int = {
    if(value < 0) 256 + value else value
  }
  
  def fromUnsignedShort(value: Short) : Int = {
    if(value < 0) 65536 + value else value
  }
  
  def fromUnsignedInt(value: Int) : Long = {
    if(value < 0) 4294967296L + value else value
  }
  
  def putUnsignedByte(bsb: ByteStringBuilder, value: Int): ByteStringBuilder = {
    bsb.putByte(toUnsignedByte(value))
  }
  
  def getUnsignedByte(iterator: ByteIterator): Int = {
    fromUnsignedByte(iterator.getByte)
  }
  
  def getUnsignedByte(bs: ByteString): Int = {
    getUnsignedByte(bs.iterator)
  }
  
  def putUnsignedShort(bsb: ByteStringBuilder, value: Int): ByteStringBuilder = {
    bsb.putShort(toUnsignedShort(value))
  }
  
  def getUnsignedShort(iterator: ByteIterator): Int = {
    fromUnsignedShort(iterator.getShort)
  }
  
  def getUnsignedShort(bs: ByteString): Int = {
    getUnsignedShort(bs.iterator)
  }
  
  def putUnsigned24(bsb: ByteStringBuilder, value: Int) : ByteStringBuilder = {
   bsb.putByte(toUnsignedByte(value / 65536)).putShort(toUnsignedShort(value % 65536))
  }
  
  def getUnsigned24(iterator: ByteIterator) : Int = {
    65536 * fromUnsignedByte(iterator.getByte) + fromUnsignedShort(iterator.getShort)
  }
  
  def getUnsigned24(bs: ByteString) : Int = {
    getUnsigned24(bs.iterator)
  }
  
  def putUnsigned32(bsb: ByteStringBuilder, value: Long) : ByteStringBuilder = {
    if(value <= 2147483647L) bsb.putInt(value.toInt) else bsb.putInt((value - 4294967296L).toInt)
  }
  
  def getUnsigned32(iterator: ByteIterator): Long = {
    65536L * fromUnsignedShort(iterator.getShort) + fromUnsignedShort(iterator.getShort)
  }
  
  def getUnsigned32(bs: ByteString) : Long = {
    getUnsigned32(bs.iterator)
  }
  
  // Does not work for values bigger than 2^63
  def putUnsigned64(bsb: ByteStringBuilder, value: Long) : ByteStringBuilder = {
    if(value <= 9223372036854775807L) bsb.putLong(value) else bsb.putLong(0)
  }
  
  def getUnsigned64(iterator: ByteIterator): Long = {
    65536L * fromUnsignedInt(iterator.getInt) + fromUnsignedInt(iterator.getInt)
  }
  
  def getUnsigned64(bs: ByteString) : Long = {
    getUnsigned32(bs.iterator)
  }
}



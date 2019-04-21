package yaas.util

object Net {
  
  /**
   * Gets a <network>/<mask> or <network> and extract the network and the mask parts.
   * 
   * Mask defaults to 32 if not specified
   */
  def decomposeNetwork(network: String) = {
    val netComponents = network.split("/")
      (
          // Network part
          java.net.InetAddress.getByName(netComponents(0)),
          // Mask part
          if(netComponents.length == 1) 32 else netComponents(1).toInt
      )
  }
  
  /**
   * Applies the specified mask to the address
   */
  def masked(address: Array[Byte], mask: Int) = {
    
    // acc: bytes already masked
    // addr: bytes to be still masked
    // mask: remaining size of the mask
    def maskedAcc(acc: Array[Byte], addr: Array[Byte], mask: Int): Array[Byte] = {
      if(addr.size == 0) acc
      else {
        val nextByte: Byte =
          if(mask <= 0) 0
          else if(mask > 8) addr(0)
          else (addr(0) & (255 << (8 - mask))).toByte

        maskedAcc(acc :+ nextByte, addr.slice(1, addr.size), mask - 8)
      }
    }
    
    maskedAcc(Array(), address, mask)
  }
  
  def isAddressInNetwork(address: String, network: String) = {
    val (networkAddr, networkMask) = decomposeNetwork(network)
    
    masked(java.net.InetAddress.getByName(address).getAddress, networkMask).sameElements(networkAddr.getAddress)
    
  }
}
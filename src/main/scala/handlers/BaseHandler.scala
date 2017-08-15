package handlers

import diameterServer._

object BaseHandler extends DiameterApplicationHandler {
  
  println("Base handler is here!")
  
  def handleMessage = {
    println("Test handler is handling a message!")
  }
}
package orders

import akka.actor.Actor
import orders.MarketingActor.{MarketingQuery, MarketingResult}
import orders.OrderActor.OrderItem

/**
  * Created by cuitao-pc on 16/5/19.
  */
object MarketingActor {

  sealed trait Cmd

  case class MarketingQuery(merTermId: String, userId: String, item: List[OrderItem], deliveryId: Long) extends Cmd
  case class MarketingResult(marketingId: String, resultState: Boolean, msg: String,  deliveryId: Long) extends Cmd

}

class MarketingActor extends Actor {
  override def receive: Receive = {
    case MarketingQuery(merTermId, userId, item, deliveryId) =>
      val totalAmt = item.foldLeft(0)((t, o) => t + o.price * o.quantity)
      sender() ! OrderActor.ConfirmCmd(deliveryId)
      sender() ! OrderActor.MarketingCmd("001", merTermId, userId, totalAmt / 2, "测试优惠, 一律5折")
    case MarketingResult(marketingId, resultState, msg, deliveryId) =>
      sender() ! OrderActor.ConfirmCmd(deliveryId)
  }
}

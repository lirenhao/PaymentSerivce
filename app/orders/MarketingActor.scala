package orders

import akka.actor.Actor
import orders.MarketingActor.MarketingQuery
import orders.OrderActor.OrderItem

/**
  * Created by cuitao-pc on 16/5/19.
  */
object MarketingActor {

  sealed trait Cmd

  case class MarketingQuery(merTermId: String, userId: String, item: List[OrderItem])

}

class MarketingActor extends Actor {
  override def receive: Receive = {
    case MarketingQuery(merTermId, userId, item) => sender() ! OrderActor.MarketingCmd(1000, "测试优惠, 一律十块")
  }
}

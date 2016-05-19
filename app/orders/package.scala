import akka.actor.{ActorSystem, Props}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by cuitao-pc on 16/5/17.
  */
package object orders {

  object TerminalType extends Enumeration {
    type TerminalType = Value
    val MERCHANT, USER = Value
  }

  val orderActorSystem = ActorSystem("orders")

  // TODO: 还是依赖问题
  val clientSessionBridgeActorRef = orderActorSystem.actorOf(Props[ClientSessionBridgeActor])
  val orderManagerActorRef = orderActorSystem.actorOf(Props(new OrderManagerActor(clientSessionBridgeActorRef)))

  orderActorSystem.scheduler.schedule(8 hour, 8 hour, orderManagerActorRef, OrderManagerActor.OrderManagerSnapCmd)

  val marketingActorRef = orderActorSystem.actorOf(Props[MarketingActor])
}

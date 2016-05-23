package controllers

import javax.inject.Inject

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.stream.Materializer
import akka.util.Timeout
import orders.OrderActor.OrderItem
import orders.TerminalType.TerminalType
import orders._
import play.api.libs.json.JsValue
import play.api.libs.streams.ActorFlow
import play.api.mvc.WebSocket

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Created by cuita on 2016/4/30.
  */
class MyWS @Inject()(implicit system: ActorSystem, materializer: Materializer) {
  def socket = WebSocket.accept[JsValue, JsValue] { request =>
    ActorFlow.actorRef(out => MyWebSocketActor.props(out))
  }
}

object EventType extends Enumeration {
  type EventType = Value
  val CLIENT_SIGN_IN, ORDER_ITEMS, MARKETING = Value
}
class MyWebSocketActor(out: ActorRef) extends Actor {
  var terminalType: TerminalType = null
  var terminalId: String = null
  implicit var timeout = Timeout(5 second)

  def receive = {
    case msg: JsValue =>
      println(msg)
      (msg \ "eventType").as[String] match {
        case "CLIENT_SIGN_IN" =>
          TerminalType.values.find( e => e.toString == (msg \ "terminalType").as[String]).foreach{
            t =>
              terminalId = (msg \ "id").as[String]
              terminalType = t
              clientSessionBridgeActorRef ! ClientSessionBridgeActor.Register(terminalId, terminalType)
          }
        case "CREATE_ORDER" =>
          val id = (msg \ "id").as[String]
          val items = (msg \ "products").as[List[JsValue]].map(jv => OrderItem(name = (jv \ "name").as[String], price = (jv \ "price").as[Int], quantity = (jv \ "quantity").as[Int]))
          (orderManagerActorRef ? OrderManagerActor.CreateOrderCmd).mapTo[String].foreach{
            orderId =>
              sendToOrder(orderId, OrderActor.InitCmd(items, id))
          }
          println(items)
        case "JOIN_ORDER" =>
          sendToOrder((msg \ "orderId").as[String], OrderActor.JoinCmd((msg \ "id").as[String]))
        case "CANCEL_ORDER" =>
          sendToOrder((msg \ "orderId").as[String], OrderActor.CancelCmd)
        case "PAY_AUTH_REQ" =>
          sendToOrder((msg \ "orderId").as[String], OrderActor.PayAuthReqCmd)
        case _ =>
      }
    case OrderActor.OrderMessage(jsValue) =>
      out ! jsValue
  }

  @inline
  private def sendToOrder(orderId: String, cmd: OrderActor.Cmd): Unit = {
    clientSessionBridgeActorRef ! ClientSessionBridgeActor.OrderActorRefForwardCmd(orderId, cmd)
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    super.postStop()
    println("ct: stop!")
  }
}

object MyWebSocketActor {
  def props(out: ActorRef) = Props(new MyWebSocketActor(out))
}
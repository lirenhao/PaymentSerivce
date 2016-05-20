package orders

import akka.actor.{Actor, ActorRef, Terminated}
import orders.TerminalType._
import play.api.libs.json.JsValue

import scala.collection.mutable

/**
  * Created by cuitao-pc on 16/5/15.
  */

object ClientSessionBridgeActor {

  sealed trait Cmd

  case class Register(id: String, terminalType: TerminalType) extends Cmd

  case class MessageCmd(merId: String, userId: String, orderMessage: OrderActor.OrderMessage) extends Cmd

  case class OrderActorRefForwardCmd(orderId: String, cmd: OrderActor.Cmd) extends Cmd

}

class ClientSessionBridgeActor extends Actor {

  import ClientSessionBridgeActor._

  private class ActorRefRelation(terminalType: TerminalType) {
    private val map1 = mutable.Map[ActorRef, String]()
    private val map2 = mutable.Map[String, mutable.Set[ActorRef]]()

    def add(id: String, actorRef: ActorRef): Unit = {
      map1.get(actorRef).fold[Unit]{
        map1.update(actorRef, id)
        map2.update(id, map2.getOrElse(id, mutable.Set.empty[ActorRef]) += actorRef)
      } {
        _id =>
          if(id != _id) {
            orderManagerActorRef.tell(OrderManagerActor.OrderActorRefsForwardCmd(_id, terminalType, OrderActor.GiveUpPayCmd), actorRef)
            map1.update(actorRef, id)
            map2.update(id, map2.getOrElse(id, mutable.Set.empty[ActorRef]) += actorRef)
          }
      }
    }

    def getId(actorRef: ActorRef) = map1.get(actorRef)

    def getActorRefs(id: String) = map2.getOrElse(id, mutable.Set.empty[ActorRef])

    def removeBy(actorRef: ActorRef) = map1.remove(actorRef).foreach{
      id =>
        val set = map2(id)
        set -= actorRef
        if(set.isEmpty) map2.remove(id)
    }

    def removeBy(id: String) = map2.remove(id).foreach(_.foreach(map1.remove))
  }

  private val merTermMap = new ActorRefRelation(MERCHANT)
  private val userMap = new ActorRefRelation(USER)

  override def receive: Receive = {
    case Register(id, terminalType) =>
      val s = sender()
      context.watch(s)
      terminalType match {
        case MERCHANT => merTermMap.add(id, s)
        case USER => userMap.add(id, s)
      }
      orderManagerActorRef.tell(OrderManagerActor.OrderActorRefsForwardCmd(id, terminalType, OrderActor.GetOrderState), s)
    case MessageCmd(merTermId, userId, orderMessage) =>
      def send(id: String, map: ActorRefRelation): Unit = {
        map.getActorRefs(id).foreach(_ ! orderMessage)
      }
      send(merTermId, merTermMap)
      send(userId, userMap)
    case OrderActorRefForwardCmd(orderId, cmd) =>
      orderManagerActorRef.tell(OrderManagerActor.OrderActorRefForwardCmd(orderId, cmd), sender())
    case Terminated(ref) => merTermMap.removeBy(ref); userMap.removeBy(ref)
  }
}

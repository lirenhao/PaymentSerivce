package orders

import akka.actor.{ActorRef, ActorSelection, Terminated}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.forkjoin.LinkedTransferQueue

/**
  * Created by cuitao-pc on 16/5/11.
  */
object OrderActor {

  sealed trait Cmd

  case class InitCmd(items: List[OrderItem], merTermId: String) extends Cmd

  case class JoinCmd(id: String) extends Cmd

  case class LeaveCmd(id: String) extends Cmd

  case object PayAuthReqCmd extends Cmd

  case object GiveUpPayCmd extends Cmd

  case object CancelCmd extends Cmd

  case class MarketingCmd(amt: Int, msg: String) extends Cmd

  case class PayResultCmd(state: Boolean, msg: String) extends Cmd

  case class ConfirmCmd(deliveryId: Long) extends Cmd

  case object GetOrderState extends Cmd

  case object Shutdown extends Cmd

  sealed trait Evt

  case class InitEvt(items: List[OrderItem], merTermId: String, createTime: Int) extends Evt

  case class JoinEvt(id: String) extends Evt

  case class LeaveEvt(id: String) extends Evt

  case class MarketingEvt(amt: Int, msg: String) extends Evt

  case class ConfirmEvt(deliveryId: Long) extends Evt

  case class PayResultEvt(state: Boolean, msg: String) extends Evt

  case class OrderItem(name: String, price: Int, quantity: Int)

  case class Marketing(amt: Int, msg: String)

  case class OrderMessage(jsValue: JsValue)

  trait State

  case class OrderState(items: Option[List[OrderItem]], marketing: Option[Marketing], merTermId: String, userIds: Set[String], state: Option[(Boolean, String)], createTime: Int) extends State

}

class OrderActor(orderId: String, orderManagerActorSelection: ActorSelection, clientBridge: ActorRef) extends PersistentActor with AtLeastOnceDelivery {

  import OrderActor._
  import TerminalType._

  private class PayQueue() {
    private var currentPayActorRef = Option[ActorRef](null)
    private val queue = new LinkedTransferQueue[ActorRef]()

    def test(): Unit = if (currentPayActorRef.isEmpty) {
      currentPayActorRef = Some(queue.poll())
      currentPayActorRef.foreach(_ ! makePayAuthJsValue)
    }

    def payReq(actorRef: ActorRef): Unit = {
      if (!queue.contains(actorRef))
        queue.offer(actorRef)

      test()
    }

    def givUp(actorRef: ActorRef): Unit = {
      if (queue.contains(actorRef))
        queue.remove(actorRef)

      if (currentPayActorRef.isDefined && currentPayActorRef.get == actorRef)
        currentPayActorRef = Option(null)

      test()
    }
  }

  private var state = OrderState(None, None, null, Set.empty, None, 0)
  private val payQueue = new PayQueue()

  override def receiveRecover: Receive = {
    case e: Evt => updateState(e)
  }

  def updateState(event: Evt): Unit = event match {
    case InitEvt(items, merTermId, createTime) =>
      state = state.copy(items = Option(items), merTermId = merTermId, createTime = createTime)
      deliver(orderManagerActorSelection)(deliveryId => OrderManagerActor.BuildRelationCmd(merTermId, MERCHANT, orderId, deliveryId))
    case JoinEvt(id) =>
      state = state.copy(userIds = state.userIds + id)
      deliver(orderManagerActorSelection)(deliveryId => OrderManagerActor.BuildRelationCmd(id, USER, orderId, deliveryId))
    case LeaveEvt(id) =>
      state = state.copy(userIds = state.userIds - id)
      deliver(orderManagerActorSelection)(deliveryId => OrderManagerActor.DeleteRelationCmd(id, USER, orderId, deliveryId))
    case MarketingEvt(amt, msg) =>
      state = state.copy(marketing = Option(Marketing(amt, msg)))
    case PayResultEvt(resultState, msg) =>
      state = state.copy(state = Some(resultState, msg))
      deliver(orderManagerActorSelection)(deliveryId => OrderManagerActor.DeleteActiveOrderCmd(orderId, deliveryId))
    case ConfirmEvt(deliveryId) => confirmDelivery(deliveryId)
  }

  override def receiveCommand: Receive = {
    case cmd: Cmd =>
      println(cmd)
      handleCmd(cmd)
    case Terminated(ref) => payQueue.givUp(ref)

  }

  def handleCmd(cmd: Cmd): Unit = cmd match {
    case InitCmd(items, merTermId) =>
      if (state.items.isEmpty)
        persist(InitEvt(items, merTermId, (System.currentTimeMillis() / 1000).toInt))(updateState)
    case JoinCmd(id) =>
      if (!state.userIds.contains(id)) {
        val s = sender()
        persist(JoinEvt(id)) {
          event =>
            updateState(event)
            List(makeOrderJsValue, makeMarketingJsValue).flatten.foreach(s ! OrderMessage(_))
        }
      }
    case LeaveCmd(id) =>
      if (state.userIds.contains(id))
        persist(LeaveEvt(id))(updateState)
    case MarketingCmd(amt, msg) =>
      if (state.marketing.isEmpty)
        persist(MarketingEvt(amt, msg)) {
          event =>
            updateState(event)
            clientBridge ! ClientSessionBridgeActor.MessageCmd(
              Set(state.merTermId), state.userIds, OrderMessage(makeMarketingJsValue.get)
            )
        }
    case GetOrderState => List(makeOrderJsValue, makeMarketingJsValue).flatten.foreach(sender() ! OrderMessage(_))
    case PayAuthReqCmd =>
      context.watch(sender())
      payQueue.payReq(sender())
    case GiveUpPayCmd =>
      context.unwatch(sender())
      payQueue.givUp(sender())
    case ConfirmCmd(deliveryId) => persist(ConfirmEvt(deliveryId))(updateState)
    case PayResultCmd(resultState, msg) =>
      persist(PayResultEvt(resultState, msg)) {
        event =>
          updateState(event)
          clientBridge ! ClientSessionBridgeActor.MessageCmd(
            Set(state.merTermId), state.userIds, OrderMessage(makePayResultJsValue)
          )
      }
    case CancelCmd => persist(PayResultEvt(state = false, "取消"))(updateState)
    case Shutdown => context.stop(self)
  }

  def makeOrderJsValue(item: Option[List[OrderItem]]): Option[JsValue] = item.map(list => Json.obj("eventType" -> "ORDER_ITEMS", "orderId" -> orderId, "products" ->
    Json.toJson(
      list.map(i => Json.obj("name" -> i.name, "price" -> i.price, "quantity" -> i.quantity))
    )
  ))

  def makeOrderJsValue: Option[JsValue] = makeOrderJsValue(state.items)

  def makeMarketingJsValue: Option[JsValue] = makeMarketingJsValue(state.marketing)

  def makeMarketingJsValue(marketing: Option[Marketing]): Option[JsValue] = marketing.map(mk => Json.obj("eventType" -> "MARKETING", "orderId" -> orderId, "amt" -> mk.amt, "msg" -> mk.msg))

  def makeMarketingJsValue(amt: Int, msg: String): Option[JsValue] = makeMarketingJsValue(Some(Marketing(amt, msg)))

  def makePayAuthJsValue = Json.obj("eventType" -> "PAY_AUTH", "orderId" -> orderId)

  def makePayResultJsValue = Json.obj("eventType" -> "PAY_COMPLETED", "orderId" -> orderId, "result" -> state.state.get._1, "msg" -> state.state.get._2)

  override def persistenceId: String = orderId

}

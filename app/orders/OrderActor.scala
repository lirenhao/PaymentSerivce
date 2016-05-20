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

  case object PayAuthReqCmd extends Cmd

  case object GiveUpPayCmd extends Cmd

  case object CancelCmd extends Cmd

  case class MarketingCmd(marketingId: String, merTermId: String, userId: String, amt: Int, msg: String) extends Cmd

  case class PayResultCmd(state: Boolean, msg: String) extends Cmd

  case class ConfirmCmd(deliveryId: Long) extends Cmd

  case object GetOrderState extends Cmd

  case object Shutdown extends Cmd

  sealed trait Evt

  case class InitEvt(items: List[OrderItem], merTermId: String, createTime: Int) extends Evt

  case class JoinEvt(id: String) extends Evt

  case class MarketingEvt(marketingId: String, merTermId: String, userId: String, amt: Int, msg: String) extends Evt

  case class ConfirmEvt(deliveryId: Long) extends Evt

  case class PayResultEvt(state: Boolean, msg: String) extends Evt

  case class OrderItem(name: String, price: Int, quantity: Int)

  case class Marketing(marketingId: String, amt: Int, msg: String)

  case class OrderMessage(jsValue: JsValue)

  trait State

  case class OrderState(items: List[OrderItem], marketing: Option[Marketing], merTermId: String, userId: Option[String], state: Option[(Boolean, String)], createTime: Int) extends State

}

class OrderActor(orderId: String, orderManagerActorSelection: ActorSelection, marketingActorSelection: ActorSelection, clientBridge: ActorSelection) extends PersistentActor with AtLeastOnceDelivery {

  import OrderActor._
  import TerminalType._

  private class PayQueue() {
    private var currentPayActorRef = Option[ActorRef](null)
    private val queue = new LinkedTransferQueue[ActorRef]()

    def test(): Unit = if (currentPayActorRef.isEmpty) {
      currentPayActorRef = Option(queue.poll())
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

    def noPay = queue.isEmpty && currentPayActorRef.isEmpty
  }

  private var state = OrderState(List.empty, None, null, Option(null), None, 0)
  private val payQueue = new PayQueue()

  override def receiveRecover: Receive = {
    case e: Evt => updateState(e)
  }

  def updateState(event: Evt): Unit = event match {
    case InitEvt(items, merTermId, createTime) =>
      deliverBuildRelation(merTermId, MERCHANT)
      state = state.copy(items = items, merTermId = merTermId, createTime = createTime)
    case JoinEvt(id) =>
      if (state.userId.isDefined) {
        deliverDeleteRelation(state.userId.get, USER)
        deliverMarketingResult(resultState = false, "切换用户")
      }
      deliverMarketingQuery(id)
      deliverBuildRelation(id, USER)
      state = state.copy(userId = Option(id), marketing = Option(null))
    case MarketingEvt(marketingId, merTermId, userId, amt, msg) =>
      if (state.merTermId == merTermId && state.userId.fold(false)(userId == _))
        state = state.copy(marketing = Option(Marketing(marketingId, amt, msg)))
      else
        deliverMarketingResult(marketingId, resultState = false, "失效")
    case PayResultEvt(resultState, msg) =>
      deliverMarketingResult(resultState, msg)
      deliverDeleteActiveOrder()
      if (!resultState && state.marketing.isDefined) state = state.copy(marketing = Option(null))
      state = state.copy(state = Option(resultState, msg))
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
      (state.userId.fold(true)(_ != id), payQueue.noPay) match {
        case (true, true) =>
          persist(JoinEvt(id)) {
            event =>
              sendMessage(makeFailJsValue("用户 " + id + " 参与支付"), merTermId = null)
              updateState(event)
              sender() ! OrderMessage(makeOrderJsValue)
          }
        case (true, false) =>
          sender() ! OrderMessage(makeMessageJsValue(MsgLevel.WARN, "订单正在支付, 当前用户不能参与支付"))
        case (_, _) =>
      }
    case MarketingCmd(marketingId, merTermId, userId, amt, msg) =>
      persist(MarketingEvt(marketingId, merTermId, userId, amt, msg)) {
        event =>
          updateState(event)
          if (state.userId.fold(false)(userId == _) && state.merTermId == merTermId)
            sendMessage(makeMarketingJsValue.get)
      }
    case GetOrderState =>
      sender() ! OrderMessage(makeOrderJsValue)
      makeMarketingJsValue.foreach(sender() ! OrderMessage(_))
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
          sendMessage(makePayResultJsValue)
      }
    case CancelCmd => persist(PayResultEvt(state = false, "取消"))(updateState)
    case Shutdown => context.stop(self)
  }

  private def sendMessage(jsValue: JsValue, merTermId: String = state.merTermId, userId: String = state.userId.orNull): Unit = {
    if (merTermId != null || userId != null)
      clientBridge ! ClientSessionBridgeActor.MessageCmd(
        merTermId, userId, OrderMessage(makePayResultJsValue)
      )
  }

  def makeOrderJsValue(item: List[OrderItem]): JsValue = Json.obj("eventType" -> "ORDER_ITEMS", "orderId" -> orderId, "products" ->
    Json.toJson(
      item.map(i => Json.obj("name" -> i.name, "price" -> i.price, "quantity" -> i.quantity))
    )
  )

  @inline
  private def deliverBuildRelation(id: String, terminalType: TerminalType): Unit = {
    deliver(orderManagerActorSelection)(deliveryId => OrderManagerActor.BuildRelationCmd(id, terminalType, orderId, deliveryId))
  }

  @inline
  private def deliverDeleteRelation(id: String, terminalType: TerminalType): Unit = {
    deliver(orderManagerActorSelection)(deliveryId => OrderManagerActor.DeleteRelationCmd(id, terminalType, orderId, deliveryId))
  }

  @inline
  private def deliverDeleteActiveOrder(): Unit = {
    deliver(orderManagerActorSelection)(deliveryId => OrderManagerActor.DeleteActiveOrderCmd(orderId, deliveryId))
  }

  @inline
  private def deliverMarketingResult(marketingId: String, resultState: Boolean, msg: String): Unit = {
    deliver(marketingActorSelection)(deliveryId => MarketingActor.MarketingResult(marketingId, resultState, msg, deliveryId))
  }

  @inline
  private def deliverMarketingResult(resultState: Boolean, msg: String): Unit = {
    state.marketing.foreach {
      mk =>
        deliverMarketingResult(mk.marketingId, resultState, msg)
    }
  }

  @inline
  private def deliverMarketingQuery(userId: String): Unit = {
    deliver(marketingActorSelection)(deliveryId => MarketingActor.MarketingQuery(state.merTermId, userId, state.items, deliveryId))
  }

  @inline
  private def makeOrderJsValue: JsValue = makeOrderJsValue(state.items)

  @inline
  private def makeMarketingJsValue: Option[JsValue] = makeMarketingJsValue(state.marketing)

  @inline
  private def makeMarketingJsValue(marketing: Option[Marketing]): Option[JsValue] = marketing.map(mk => Json.obj("eventType" -> "MARKETING", "orderId" -> orderId, "amt" -> mk.amt, "msg" -> mk.msg))

  @inline
  private def makePayAuthJsValue = Json.obj("eventType" -> "PAY_AUTH", "orderId" -> orderId)

  @inline
  private def makePayResultJsValue = Json.obj("eventType" -> "PAY_COMPLETED", "orderId" -> orderId, "result" -> state.state.get._1, "msg" -> state.state.get._2)

  @inline
  private def makeFailJsValue(msg: String) = Json.obj("eventType" -> "FAIL", "orderId" -> orderId, "msg" -> msg)

  private object MsgLevel extends Enumeration {
    type MsgLevel = Value
    val NONE, WARN, ERR = Value
  }

  @inline
  def makeMessageJsValue(level: MsgLevel.MsgLevel, msg: String) = Json.obj("eventType" -> "MESSAGE", "orderId" -> orderId, "level" -> level.toString, "msg" -> msg)

  override def persistenceId: String = orderId

}

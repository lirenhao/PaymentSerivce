package orders

import akka.actor.{ActorRef, ActorSelection, Props, Status, Terminated}
import akka.persistence.{PersistentActor, SaveSnapshotSuccess, SnapshotOffer}
import orders.TerminalType._

/**
  * Created by cuitao-pc on 16/5/11.
  */
object OrderManagerActor {

  sealed trait Cmd

  case object CreateOrderCmd extends Cmd

  case class DeleteActiveOrderCmd(orderId: String, deliveryId: Long) extends Cmd

  case class OrderActorRefsForwardCmd(id: String, terminalType: TerminalType, cmd: OrderActor.Cmd) extends Cmd

  case class OrderActorRefForwardCmd(orderId: String, cmd: OrderActor.Cmd) extends Cmd

  case class BuildRelationCmd(id: String, terminalType: TerminalType, orderId: String, deliveryId: Long) extends Cmd

  case class DeleteRelationCmd(id: String, terminalType: TerminalType, orderId: String, deliveryId: Long) extends Cmd

  case object OrderManagerSnapCmd extends Cmd

  def lazyMap[K, V](z: K => V): (K => V, K => Option[V], K => Boolean, V => Unit) = {
    val map = scala.collection.mutable.Map[K, V]()
    (
      k => map.getOrElseUpdate(k, z(k)),
      k => map.remove(k),
      k => map.contains(k),
      v => map.map(_.swap).get(v).foreach(map.remove)
      )
  }

  sealed trait Evt

  case object AddOrderEvt extends Evt

  case class RemoveOrderEvt(order: String) extends Evt

  case class AddRelationEvent(id: String, terminalType: TerminalType, orderId: String) extends Evt

  case class RemoveRelationEvent(id: String, terminalType: TerminalType, orderId: String) extends Evt

  trait State

  case class OrderManagerState(moRelation: Map[String, Set[String]], uoRelation: Map[String, Set[String]], activeOrders: Set[String], seqId: Int) extends State

}

class OrderManagerActor(clientBridgeSelection: ActorSelection, marketingSelection: ActorSelection) extends PersistentActor {

  import OrderManagerActor._

  private lazy val selfSelection = context.system.actorSelection(self.path)
  private var state = OrderManagerState(Map.empty, Map.empty, Set[String](), 0)
  private val (getOrderRef, removeByOrderId, existOrderRef, removeByOrderActorRef) = lazyMap[String, ActorRef] {
    orderId =>
      context.watch(context.system.actorOf(Props(new OrderActor(orderId, selfSelection, marketingSelection, clientBridgeSelection))))
  }

  def updateState(event: Evt): Unit = event match {
    case AddOrderEvt =>
      val seqId = state.seqId + 1
      state = state.copy(seqId = seqId, activeOrders = state.activeOrders + seqId.toString)
    case RemoveOrderEvt(orderId) =>
      state = state.copy(activeOrders = state.activeOrders - orderId)
    case AddRelationEvent(id, terminalType, orderId) =>
      def addRelation(map: Map[String, Set[String]]) = {
        map.updated(id, map.get(id) match {
          case Some(set) => set + orderId
          case None => Set(orderId)
        })
      }
      state = terminalType match {
        case USER =>
          state.copy(uoRelation = addRelation(state.uoRelation))
        case MERCHANT =>
          state.copy(moRelation = addRelation(state.moRelation))
      }
    case RemoveRelationEvent(id, terminalType, orderId) =>
      def removeRelation(map: Map[String, Set[String]]) = {
        val set = map.get(id).fold(Set.empty[String])(_ - orderId)
        if (set.isEmpty)
          map - id
        else
          map.updated(id, set)
      }
      state = terminalType match {
        case USER =>
          state.copy(uoRelation = removeRelation(state.uoRelation))
        case MERCHANT =>
          state.copy(moRelation = removeRelation(state.uoRelation))
      }
  }

  override def receiveRecover: Receive = {
    case e: Evt => updateState(e)
    case SnapshotOffer(_, state: OrderManagerState) => this.state = state
  }

  def handleCmd(cmd: Cmd): Unit = cmd match {
    case CreateOrderCmd =>
      persist(AddOrderEvt) {
        event =>
          updateState(event)
          sender() ! state.seqId.toString
      }
    case DeleteActiveOrderCmd(orderId, deliveryId) =>
      if (state.activeOrders.contains(orderId))
        persist(RemoveOrderEvt(orderId)) {
          event =>
            updateState(event)
            sender() ! OrderActor.ConfirmCmd(deliveryId)

            if (existOrderRef(orderId)) {
              context.unwatch(getOrderRef(orderId)) ! OrderActor.Shutdown
              removeByOrderId(orderId)
            }
        }
      else
        sender() ! OrderActor.ConfirmCmd(deliveryId)
    case OrderActorRefForwardCmd(orderId, orderCmd) =>
      if (state.activeOrders.contains(orderId))
        getOrderRef(orderId).tell(orderCmd, sender())
      else
        sender() ! Status.Failure(new NoSuchElementException("未找到有效订单: " + orderId))
    case OrderActorRefsForwardCmd(id, terminalType, orderCmd) =>
      val relation = terminalType match {
        case USER => state.uoRelation
        case MERCHANT => state.moRelation
      }
      relation.get(id).fold(Set.empty[ActorRef])(_.map(getOrderRef)).foreach(_.tell(orderCmd, sender()))
    case BuildRelationCmd(id, terminalType, orderId, deliveryId) =>
      if (state.activeOrders.contains(orderId))
        persist(AddRelationEvent(id, terminalType, orderId)) {
          event =>
            updateState(event)
            sender() ! OrderActor.ConfirmCmd(deliveryId)
        }
      else
        sender() ! OrderActor.ConfirmCmd(deliveryId)
    case DeleteRelationCmd(id, terminalType, orderId, deliveryId) =>
      val relation = terminalType match {
        case USER => state.uoRelation
        case MERCHANT => state.moRelation
      }
      relation.get(id).flatMap(_.contains(orderId) match {
        case true => Some(orderId)
        case false => None
      }).fold(sender() ! OrderActor.ConfirmCmd(deliveryId)) {
        _ =>
          persist(RemoveRelationEvent(id, terminalType, orderId)) {
            event =>
              updateState(event)
              sender() ! OrderActor.ConfirmCmd(deliveryId)
          }
      }
    case OrderManagerSnapCmd => this.saveSnapshot(state)
  }

  override def receiveCommand: Receive = {
    case cmd: Cmd =>
      println(cmd)
      handleCmd(cmd)
    case SaveSnapshotSuccess(metadata) => deleteMessages(metadata.sequenceNr)
    case Terminated(ref) => removeByOrderActorRef(ref)
  }

  override def persistenceId: String = "order-manager"
}
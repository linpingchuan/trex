package com.github.simbo1905.trex.library

case class PaxosAgent[RemoteRef](nodeUniqueId: Int, role: PaxosRole, data: PaxosData[RemoteRef])

case class PaxosEvent[RemoteRef](io: PaxosIO[RemoteRef], agent: PaxosAgent[RemoteRef], message: PaxosMessage)

trait PaxosIO[RemoteRef] {
  def journal: Journal

  def plog: PaxosLogging

  def randomTimeout: Long

  def clock: Long

  def deliver(value: CommandValue): Any

  def respond(client: RemoteRef, data: Any)

  def send(msg: PaxosMessage)

  def sendNoLongerLeader(clientCommands: Map[Identifier, (CommandValue, RemoteRef)]): Unit

  def minPrepare: Prepare

  def sender: RemoteRef
}

object PaxosAlgorithm {
  type PaxosFunction[RemoteRef] = PartialFunction[PaxosEvent[RemoteRef], PaxosAgent[RemoteRef]]
}

class PaxosAlgorithm[RemoteRef] extends PaxosLenses[RemoteRef]
with CommitHandler[RemoteRef]
with FollowerHandler[RemoteRef]
with RetransmitHandler[RemoteRef]
with PrepareHandler[RemoteRef]
with HighAcceptHandler[RemoteRef]
with PrepareResponseHandler[RemoteRef]
with AcceptResponseHandler[RemoteRef]
with ResendHandler[RemoteRef]
with ReturnToFollowerHandler[RemoteRef]
with ClientCommandHandler[RemoteRef] {

  import PaxosAlgorithm._

  val followingFunction: PaxosFunction[RemoteRef] = {
    // update heartbeat and attempt to commit contiguous accept messages
    case PaxosEvent(io, agent@PaxosAgent(_, Follower, _), c@Commit(i, heartbeat)) =>
      handleFollowerCommit(io, agent, c)
    case PaxosEvent(io, agent@PaxosAgent(_, Follower, PaxosData(_, _, to, _, _, _, _, _)), CheckTimeout) if io.clock >= to =>
      handleFollowerTimeout(io, agent)
    case PaxosEvent(io, agent, vote: PrepareResponse) if agent.role == Follower =>
      handelFollowerPrepareResponse(io, agent, vote)
    // ignore an accept response which may be seen after we backdown to follower
    case PaxosEvent(_, agent@PaxosAgent(_, Follower, _), vote: AcceptResponse) =>
      agent
  }

  val retransmissionStateFunction: PaxosFunction[RemoteRef] = {
    case PaxosEvent(io, agent, rq: RetransmitRequest) =>
      handleRetransmitRequest(io, agent, rq)

    case PaxosEvent(io, agent, rs: RetransmitResponse) =>
      handleRetransmitResponse(io, agent, rs)
  }

  val prepareStateFunction: PaxosFunction[RemoteRef] = {
    case PaxosEvent(io, agent, p@Prepare(id)) =>
      handlePrepare(io, agent, p)
  }

  // FIXME push the matching logic down into a handler
  val acceptStateFunction: PaxosFunction[RemoteRef] = {
    // nack lower accept
    case PaxosEvent(io, agent, a@Accept(id, _)) if id.number < agent.data.progress.highestPromised =>
      io.send(AcceptNack(id, agent.nodeUniqueId, agent.data.progress))
      agent

    // nack higher accept for slot which is committed
    case PaxosEvent(io, agent, a@Accept(id, _)) if id.number > agent.data.progress.highestPromised && id.logIndex <= agent.data.progress.highestCommitted.logIndex =>
      io.send(AcceptNack(id, agent.nodeUniqueId, agent.data.progress))
      agent

    // ack accept as high as promise. if id.number > highestPromised must update highest promised in progress http://stackoverflow.com/q/29880949/329496
    case PaxosEvent(io, agent, a@Accept(id, _)) if agent.data.progress.highestPromised <= id.number =>
      handleHighAccept(io, agent, a)
  }

  val ignoreHeartbeatStateFunction: PaxosFunction[RemoteRef] = {
    // ingore a HeartBeat which has not already been handled
    case PaxosEvent(io, agent, HeartBeat) =>
      agent
  }

  // FIXME this should disappear if we push the check timeout logic down into handlers
  /**
   * If no other logic has caught a timeout then do nothing.
   */
  val ignoreNotTimedOutCheck: PaxosFunction[RemoteRef] = {
    case PaxosEvent(_, agent, CheckTimeout) =>
      agent
  }

  val commonStateFunction: PaxosFunction[RemoteRef] =
    retransmissionStateFunction orElse
      prepareStateFunction orElse
      acceptStateFunction orElse
      ignoreHeartbeatStateFunction orElse
      ignoreNotTimedOutCheck

  val notLeaderFunction: PaxosFunction[RemoteRef] = {
    case PaxosEvent(io, agent, v: CommandValue) =>
      io.send(NotLeader(agent.nodeUniqueId, v.msgId))
      agent
  }

  val followerFunction: PaxosFunction[RemoteRef] = followingFunction orElse notLeaderFunction orElse commonStateFunction

  val takeoverFunction: PaxosFunction[RemoteRef] = {
    case PaxosEvent(io, agent, vote: PrepareResponse) =>
      handlePrepareResponse(io, agent, vote)
  }

  val acceptResponseFunction: PaxosFunction[RemoteRef] = {
    case PaxosEvent(io, agent, vote: AcceptResponse) =>
      handleAcceptResponse(io, agent, vote)
  }

  // FIXME extract to a handler
  /**
   * Here on a timeout we deal with either pending prepares or pending accepts putting a priority on prepare handling
   * which backs down easily. Only if we have dealt with all timed out prepares do we handle timed out accepts which
   * is more aggressive as it attempts to go-higher than any other node number.
   */
  val resendFunction: PaxosFunction[RemoteRef] = {
    // if we have timed-out on prepare messages
    case PaxosEvent(io, agent, CheckTimeout) if agent.data.prepareResponses.nonEmpty && io.clock > agent.data.timeout =>
      handleResendPrepares(io, agent, io.clock)

    // if we have timed-out on accept messages
    case PaxosEvent(io, agent, CheckTimeout) if agent.data.acceptResponses.nonEmpty && io.clock >= agent.data.timeout =>
      handleResendAccepts(io, agent, io.clock)
  }

  val leaderLikeFunction: PaxosFunction[RemoteRef] = {
    case PaxosEvent(io, agent, c: Commit) =>
      handleReturnToFollowerOnHigherCommit(io, agent, c)
  }

  val recoveringFunction: PaxosFunction[RemoteRef] =
    takeoverFunction orElse
      acceptResponseFunction orElse
      resendFunction orElse
      leaderLikeFunction orElse
      notLeaderFunction orElse
      commonStateFunction

  val recovererFunction: PaxosFunction[RemoteRef] = recoveringFunction orElse notLeaderFunction orElse commonStateFunction

  val leaderStateFunction: PaxosFunction[RemoteRef] = {
    // heartbeats the highest commit message
    case PaxosEvent(io, agent, HeartBeat) =>
      io.send(Commit(agent.data.progress.highestCommitted))
      agent

    // broadcasts a new client value
    case PaxosEvent(io, agent, value: CommandValue) =>
      handleClientCommand(io, agent, value, io.sender)

    // ignore late vote as we would have transitioned on a majority ack
    case PaxosEvent(io, agent, value: PrepareResponse) =>
      agent
  }

  val leaderFunction: PaxosFunction[RemoteRef] =
    leaderStateFunction orElse
      acceptResponseFunction orElse
      resendFunction orElse
      leaderLikeFunction orElse
      commonStateFunction

  def apply(e: PaxosEvent[RemoteRef]): PaxosAgent[RemoteRef] = e.agent.role match {
    case Follower => followerFunction(e)
    case Recoverer => recovererFunction(e)
    case Leader => leaderFunction(e)
  }
}

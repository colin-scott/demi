package akka.dispatch.verification

import scala.collection.mutable.Queue
import scala.collection.mutable.SynchronizedQueue
import scala.collection.mutable.HashSet
import scala.collection.mutable.HashMap

import akka.actor.Props

import scalax.collection.mutable.Graph,
       scalax.collection.GraphEdge.DiEdge,
       scalax.collection.edge.LDiEdge

import java.io.PrintWriter
import java.io.File

import org.slf4j.LoggerFactory,
       ch.qos.logback.classic.Level,
       ch.qos.logback.classic.Logger


// Example minimization pipeline:
//     fuzz()
//  -> shrinkSendContents
//  -> stsSchedDDmin
//  -> minimizeInternals
//  -> replayExperiment <- loop

// Utilities for writing Runner.scala files.
object RunnerUtils {
  val logger = LoggerFactory.getLogger("RunnerUtils")

  def fuzz(fuzzer: Fuzzer, invariant: TestOracle.Invariant,
           schedulerConfig: SchedulerConfig,
           validate_replay:Option[() => ReplayScheduler]=None,
           invariant_check_interval:Int=30,
           maxMessages:Option[Int]=None,
           randomizationStrategyCtor:() => RandomizationStrategy=() => new FullyRandom,
           computeProvenance:Boolean=true) :
        Tuple5[EventTrace, ViolationFingerprint, Graph[Unique, DiEdge], Queue[Unique], Queue[Unique]] = {
    var violationFound : ViolationFingerprint = null
    var traceFound : EventTrace = null
    var depGraph : Graph[Unique, DiEdge] = null
    var initialTrace : Queue[Unique] = null
    while (violationFound == null) {
      val fuzzTest = fuzzer.generateFuzzTest()
      logger.info("Trying: " + fuzzTest)

      // TODO(cs): it's possible for RandomScheduler to never terminate
      // (waiting for a WaitQuiescene)
      val sched = new RandomScheduler(schedulerConfig, 1,
        invariant_check_interval,
        randomizationStrategy=randomizationStrategyCtor())
      sched.setInvariant(invariant)
      maxMessages match {
        case Some(max) => sched.setMaxMessages(max)
        case None => None
      }
      Instrumenter().scheduler = sched
      sched.explore(fuzzTest) match {
        case None =>
          logger.info("Returned to main with events")
          sched.shutdown()
          logger.info("shutdown successfully")
        case Some((trace, violation)) => {
          logger.info("Found a safety violation!")
          depGraph = sched.depTracker.getGraph
          initialTrace = sched.depTracker.getInitialTrace
          sched.shutdown()
          validate_replay match {
            case Some(replayerCtor) =>
              logger.info("Validating replay")
              val replayer = replayerCtor()
              Instrumenter().scheduler = replayer
              var deterministic = true
              try {
                replayer.replay(trace.filterCheckpointMessages)
                if (replayer.violationAtEnd.isEmpty) {
                  deterministic = false
                }
              } catch {
                case r: ReplayException =>
                  logger.info("doesn't replay deterministically..." + r)
                  deterministic = false
              } finally {
                replayer.shutdown()
              }
              if (deterministic) {
                violationFound = violation
                traceFound = trace
              }
            case None =>
              violationFound = violation
              traceFound = trace
          }
        }
      }
    }

    // Before returning, try to prune events that are concurrent with the violation.
    var filtered = new Queue[Unique]
    if (computeProvenance) {
      logger.info("Pruning events not in provenance of violation. This may take awhile...")
      val provenenceTracker = new ProvenanceTracker(initialTrace, depGraph)
      val origDeliveries = countMsgEvents(traceFound.filterCheckpointMessages.filterFailureDetectorMessages)
      filtered = provenenceTracker.pruneConcurrentEvents(violationFound)
      val numberFiltered = origDeliveries - countMsgEvents(filtered.map(u => u.event))
      // TODO(cs): track this number somewhere. Or reconstruct it from
      // initialTrace/filtered.
      logger.info("Pruned " + numberFiltered + "/" + origDeliveries + " concurrent deliveries")
    }
    return (traceFound, violationFound, depGraph, initialTrace, filtered)
  }

  def deserializeExperiment(experiment_dir: String,
      messageDeserializer: MessageDeserializer,
      scheduler: ExternalEventInjector[_] with Scheduler=null, // if null, use dummy
      traceFile:String=ExperimentSerializer.event_trace):
                  Tuple3[EventTrace, ViolationFingerprint, Option[Graph[Unique, DiEdge]]] = {
    val deserializer = new ExperimentDeserializer(experiment_dir)
    val _scheduler = if (scheduler == null)
      new ReplayScheduler(SchedulerConfig())
      else scheduler
    Instrumenter().scheduler = _scheduler
    _scheduler.populateActorSystem(deserializer.get_actors)
    _scheduler.setActorNamePropPairs(deserializer.get_actors)
    val violation = deserializer.get_violation(messageDeserializer)
    val trace = deserializer.get_events(messageDeserializer,
                  Instrumenter().actorSystem, traceFile=traceFile)
    val dep_graph = deserializer.get_dep_graph()
    if (scheduler == null) {
      _scheduler.shutdown
    }
    return (trace, violation, dep_graph)
  }

  def deserializeMCS(experiment_dir: String,
      messageDeserializer: MessageDeserializer,
      scheduler: ExternalEventInjector[_] with Scheduler=null): // if null, use dummy
        Tuple5[Seq[ExternalEvent], EventTrace, ViolationFingerprint,
               Seq[Tuple2[Props, String]], MinimizationStats] = {
    val deserializer = new ExperimentDeserializer(experiment_dir)
    val _scheduler = if (scheduler == null)
      new ReplayScheduler(SchedulerConfig())
      else scheduler
    Instrumenter().scheduler = _scheduler
    _scheduler.populateActorSystem(deserializer.get_actors)
    val violation = deserializer.get_violation(messageDeserializer)
    val trace = deserializer.get_events(messageDeserializer, Instrumenter().actorSystem)
    val mcs = deserializer.get_mcs
    val actorNameProps = deserializer.get_actors
    val stats = deserializer.get_stats
    if (scheduler == null) {
      _scheduler.shutdown
    }
    return (mcs, trace, violation, actorNameProps, stats)
  }

  def replayExperiment(experiment_dir: String,
                       schedulerConfig: SchedulerConfig,
                       messageDeserializer: MessageDeserializer,
                       traceFile:String=ExperimentSerializer.event_trace): EventTrace = {
    val replayer = new ReplayScheduler(schedulerConfig, false)
    val (trace, _, _) = RunnerUtils.deserializeExperiment(experiment_dir,
                                        messageDeserializer, replayer,
                                        traceFile=traceFile)

    return RunnerUtils.replayExperiment(trace, schedulerConfig,
      Seq.empty, _replayer=Some(replayer))
  }

  def replayExperiment(trace: EventTrace,
                       schedulerConfig: SchedulerConfig,
                       actorNamePropPairs:Seq[Tuple2[Props, String]],
                       _replayer:Option[ReplayScheduler]) : EventTrace = {
    val replayer = _replayer match {
      case Some(replayer) => replayer
      case None =>
        new ReplayScheduler(schedulerConfig, false)
    }
    replayer.setActorNamePropPairs(actorNamePropPairs)
    logger.info("Trying replay:")
    Instrumenter().scheduler = replayer
    val events = replayer.replay(trace)
    logger.info("Done with replay")
    replayer.shutdown
    return events
  }

  def replay(schedulerConfig: SchedulerConfig,
             trace: EventTrace,
             actorNameProps: Seq[Tuple2[Props, String]]) {
    val replayer = new ReplayScheduler(schedulerConfig, false)
    Instrumenter().scheduler = replayer
    replayer.setActorNamePropPairs(actorNameProps)
    logger.info("Trying replay:")
    val events = replayer.replay(trace)
    logger.info("Done with replay")
    replayer.shutdown
  }

  def randomDDMin(experiment_dir: String,
                  schedulerConfig: SchedulerConfig,
                  messageDeserializer: MessageDeserializer,
                  stats: Option[MinimizationStats]=None) :
        Tuple4[Seq[ExternalEvent], MinimizationStats, Option[EventTrace], ViolationFingerprint] = {
    val sched = new RandomScheduler(schedulerConfig, 1, 0)
    val (trace, violation, _) = RunnerUtils.deserializeExperiment(experiment_dir, messageDeserializer, sched)
    sched.setMaxMessages(trace.size)

    val ddmin = new DDMin(sched, checkUnmodifed=false, stats=stats)
    val mcs = ddmin.minimize(trace.original_externals, violation)
    if (mcs.length < trace.original_externals.size) {
      logger.info("Validating MCS...")
      val validated_mcs = ddmin.verify_mcs(mcs, violation)
      validated_mcs match {
        case Some(_) => logger.info("MCS Validated!")
        case None => logger.info("MCS doesn't reproduce bug...")
      }
      return (mcs.events, ddmin._stats, validated_mcs, violation)
    } else {
      return (mcs.events, ddmin._stats, Some(trace), violation)
    }
  }

  def stsSchedDDMin(experiment_dir: String,
                    schedulerConfig: SchedulerConfig,
                    messageDeserializer: MessageDeserializer,
                    allowPeek: Boolean,
                    stats: Option[MinimizationStats]) :
        Tuple4[Seq[ExternalEvent], MinimizationStats, Option[EventTrace], ViolationFingerprint] = {
    val sched = new STSScheduler(schedulerConfig, new EventTrace, allowPeek)
    val (trace, violation, _) = RunnerUtils.deserializeExperiment(experiment_dir, messageDeserializer, sched)
    sched.original_trace = trace
    stsSchedDDMin(allowPeek, schedulerConfig, trace,
                  violation, _sched=Some(sched), stats=stats)
  }

  def stsSchedDDMin(allowPeek: Boolean,
                    schedulerConfig: SchedulerConfig,
                    trace: EventTrace,
                    violation: ViolationFingerprint,
                    initializationRoutine: Option[() => Any]=None,
                    actorNameProps: Option[Seq[Tuple2[Props, String]]]=None,
                    _sched:Option[STSScheduler]=None,
                    dag: Option[EventDag]=None,
                    stats: Option[MinimizationStats]=None) :
        Tuple4[Seq[ExternalEvent], MinimizationStats, Option[EventTrace], ViolationFingerprint] = {
    val sched = if (_sched != None) _sched.get else
                new STSScheduler(schedulerConfig, trace, allowPeek)
    Instrumenter().scheduler = sched
    if (actorNameProps != None) {
      sched.setActorNamePropPairs(actorNameProps.get)
    }

    val ddmin = new DDMin(sched, stats=stats)
    var externalsSize = 0
    val mcs = dag match {
      case Some(d) =>
        externalsSize = d.length
        ddmin.minimize(d, violation, initializationRoutine)
      case None =>
        // STSSched doesn't actually pay any attention to WaitQuiescence or
        // WaitCondition, so just get rid of them.
        val filteredQuiescence = trace.original_externals flatMap {
          case WaitQuiescence() => None
          case WaitCondition(_) => None
          case e => Some(e)
        }
        externalsSize = filteredQuiescence.size
        ddmin.minimize(filteredQuiescence, violation,
          initializationRoutine=initializationRoutine)
    }
    if (mcs.length < externalsSize) {
      printMCS(mcs.events)
      logger.info("Validating MCS...")
      var validated_mcs = ddmin.verify_mcs(mcs, violation,
        initializationRoutine=initializationRoutine)
      validated_mcs match {
        case Some(trace) =>
          logger.info("MCS Validated!")
          trace.setOriginalExternalEvents(mcs.events)
          validated_mcs = Some(trace.filterCheckpointMessages)
        case None => logger.info("MCS doesn't reproduce bug...")
      }
      return (mcs.events, ddmin._stats, validated_mcs, violation)
    } else {
      return (mcs.events, ddmin._stats, Some(trace), violation)
    }
  }

  def fungibleClocksDDMin(schedulerConfig: SchedulerConfig,
        originalTrace: EventTrace,
        violation: ViolationFingerprint,
        actorNameProps: Seq[Tuple2[Props, String]],
        initializationRoutine: Option[() => Any]=None,
        resolutionStrategy: AmbiguityResolutionStrategy=new BackTrackStrategy,
        testScheduler:TestScheduler.TestScheduler=TestScheduler.STSSched,
        depGraph: Option[Graph[Unique,DiEdge]]=None,
        preTest: Option[STSScheduler.PreTestCallback]=None,
        postTest: Option[STSScheduler.PostTestCallback]=None,
        dag: Option[EventDag]=None,
        stats: Option[MinimizationStats]=None) :
      Tuple4[Seq[ExternalEvent], MinimizationStats, Option[EventTrace], ViolationFingerprint] = {

    val oracle = new FungibleClockTestOracle(
        schedulerConfig,
        originalTrace,
        actorNameProps,
        resolutionStrategy=resolutionStrategy,
        testScheduler=testScheduler,
        depGraph=depGraph,
        preTest=preTest,
        postTest=postTest)

    val ddmin = new DDMin(oracle, stats=stats)
    var externalsSize = 0
    val mcs = dag match {
      case Some(d) =>
        externalsSize = d.length
        ddmin.minimize(d, violation, initializationRoutine)
      case None =>
        // STSSched doesn't actually pay any attention to WaitQuiescence or
        // WaitCondition, so just get rid of them.
        // TODO(cs): doesn't necessarily make sense for DPOR?
        val filteredQuiescence = originalTrace.original_externals flatMap {
          case WaitQuiescence() => None
          case WaitCondition(_) => None
          case e => Some(e)
        }
        externalsSize = filteredQuiescence.size
        ddmin.minimize(filteredQuiescence, violation,
          initializationRoutine=initializationRoutine)
    }
    if (mcs.length < externalsSize) {
      printMCS(mcs.events)
      logger.info("Validating MCS...")
      var validated_mcs = ddmin.verify_mcs(mcs, violation,
        initializationRoutine=initializationRoutine)
      validated_mcs match {
        case Some(trace) =>
          logger.info("MCS Validated!")
          trace.setOriginalExternalEvents(mcs.events)
          validated_mcs = Some(trace.filterCheckpointMessages)
        case None => logger.info("MCS doesn't reproduce bug...")
      }
      return (mcs.events, ddmin._stats, validated_mcs, violation)
    } else {
      return (mcs.events, ddmin._stats, Some(originalTrace), violation)
    }
  }

  def editDistanceDporDDMin(experiment_dir: String,
                            schedulerConfig: SchedulerConfig,
                            messageDeserializer: MessageDeserializer,
                            ignoreQuiescence:Boolean=true,
                            stats: Option[MinimizationStats]=None) :
        Tuple4[Seq[ExternalEvent], MinimizationStats, Option[EventTrace], ViolationFingerprint] = {

    val deserializer = new ExperimentDeserializer(experiment_dir)
    val actorNameProps = deserializer.get_actors

    val depGraphOpt = deserializer.get_dep_graph()
    var depGraph : Graph[Unique, DiEdge] = null
    depGraphOpt match {
      case Some(graph) =>
        depGraph = graph
      case None => throw new IllegalArgumentException("Need a DepGraph to run DPORwHeuristics")
    }
    val initialTraceOpt = deserializer.get_filtered_initial_trace()
    var initialTrace : Queue[Unique] = null
    initialTraceOpt match {
      case Some(_initialTrace) =>
        initialTrace = _initialTrace
      case None => throw new IllegalArgumentException("Need initialTrace to run DPORwHeuristics")
    }

    // Sched is just used as a dummy here to deserialize ActorRefs.
    val dummy_sched = new ReplayScheduler(schedulerConfig)
    Instrumenter().scheduler = dummy_sched
    dummy_sched.populateActorSystem(deserializer.get_actors)
    val violation = deserializer.get_violation(messageDeserializer)
    val trace = deserializer.get_events(messageDeserializer,
      Instrumenter().actorSystem).filterFailureDetectorMessages.filterCheckpointMessages

    dummy_sched.shutdown

    return editDistanceDporDDMin(schedulerConfig, trace, actorNameProps,
      depGraph, initialTrace, violation, ignoreQuiescence, stats)
  }

  def editDistanceDporDDMin(schedulerConfig: SchedulerConfig,
                            trace: EventTrace,
                            actorNameProps: Seq[Tuple2[Props, String]],
                            depGraph: Graph[Unique, DiEdge],
                            initialTrace: Queue[Unique],
                            violation: ViolationFingerprint,
                            ignoreQuiescence: Boolean,
                            stats: Option[MinimizationStats]) :
        Tuple4[Seq[ExternalEvent], MinimizationStats, Option[EventTrace], ViolationFingerprint] = {

    def dporConstructor(): DPORwHeuristics = {
      val heuristic = new ArvindDistanceOrdering
      val dpor = new DPORwHeuristics(schedulerConfig,
                          prioritizePendingUponDivergence=true,
                          invariant_check_interval=5,
                          backtrackHeuristic=heuristic)
      dpor.setInitialDepGraph(depGraph)
      dpor.setMaxMessagesToSchedule(initialTrace.size)
      dpor.setInitialTrace(new Queue[Unique] ++ initialTrace)
      dpor.setActorNameProps(actorNameProps)
      heuristic.init(dpor, initialTrace)
      return dpor
    }

    val filtered_externals = DPORwHeuristicsUtil.convertToDPORTrace(trace,
      ignoreQuiescence=ignoreQuiescence)
    val resumableDPOR = new ResumableDPOR(dporConstructor)
    val ddmin = new IncrementalDDMin(resumableDPOR,
                                     checkUnmodifed=true,
                                     stopAtSize=6, maxMaxDistance=8,
                                     stats=stats)
    val mcs = ddmin.minimize(filtered_externals, violation)

    if (mcs.length < filtered_externals.size) {
      // Verify the MCS. First, verify that DPOR can reproduce it.
      // TODO(cs): factor this out.
      logger.info("Validating MCS...")
      var verified_mcs : Option[EventTrace] = None
      val traceOpt = ddmin.verify_mcs(mcs, violation)
      traceOpt match {
        case None =>
          logger.info("MCS doesn't reproduce bug... DPOR")
        case Some(toReplay) =>
          // Now verify that ReplayScheduler can reproduce it.
          logger.info("DPOR reproduced successfully. Now trying ReplayScheduler")
          val replayer = new ReplayScheduler(schedulerConfig, false)
          Instrumenter().scheduler = replayer
          // Clean up after DPOR. Counterintuitively, use Replayer to do this, since
          // DPORwHeuristics doesn't have shutdownSemaphore.
          replayer.shutdown()
          try {
            replayer.populateActorSystem(actorNameProps)
            val replayTrace = replayer.replay(toReplay)
            replayTrace.setOriginalExternalEvents(mcs.events)
            verified_mcs = Some(replayTrace)
            logger.info("MCS Validated!")
          } catch {
            case r: ReplayException =>
              logger.info("MCS doesn't reproduce bug... ReplayScheduler")
          } finally {
            replayer.shutdown()
          }
      }
      return (mcs.events, ddmin._stats, verified_mcs, violation)
    } else {
      return (mcs.events, ddmin._stats, Some(trace), violation)
    }
  }

  def testWithStsSched(schedulerConfig: SchedulerConfig,
                       mcs: Seq[ExternalEvent],
                       trace: EventTrace,
                       actorNameProps: Seq[Tuple2[Props, String]],
                       violation: ViolationFingerprint,
                       stats: MinimizationStats,
                       initializationRoutine: Option[() => Any]=None,
                       preTest: Option[STSScheduler.PreTestCallback]=None,
                       postTest: Option[STSScheduler.PostTestCallback]=None,
                       absentIgnored: Option[STSScheduler.IgnoreAbsentCallback]=None)
                     : Option[EventTrace] = {
    val sched = new STSScheduler(schedulerConfig, trace, false)
    Instrumenter().scheduler = sched
    sched.setActorNamePropPairs(actorNameProps)
    preTest match {
      case Some(callback) =>
        sched.setPreTestCallback(callback)
      case _ =>
    }
    postTest match {
      case Some(callback) =>
        sched.setPostTestCallback(callback)
      case _ =>
    }
    absentIgnored match {
      case Some(callback) =>
        sched.setIgnoreAbsentCallback(callback)
      case _ =>
    }
    return sched.test(mcs, violation, stats, initializationRoutine=initializationRoutine)
  }

  // pre: replay(verified_mcs) reproduces the violation.
  def minimizeInternals(schedulerConfig: SchedulerConfig,
                        mcs: Seq[ExternalEvent],
                        verified_mcs: EventTrace,
                        actorNameProps: Seq[Tuple2[Props, String]],
                        violation: ViolationFingerprint,
                        removalStrategyCtor:()=>RemovalStrategy=null, // If null, use LeftToRightOneAtATime
                        preTest: Option[STSScheduler.PreTestCallback]=None,
                        postTest: Option[STSScheduler.PostTestCallback]=None,
                        initializationRoutine: Option[() => Any]=None,
                        stats: Option[MinimizationStats]=None):
      Tuple2[MinimizationStats, EventTrace] = {

    val removalStrategy = if (removalStrategyCtor == null)
        new LeftToRightOneAtATime(verified_mcs, schedulerConfig.messageFingerprinter)
        else removalStrategyCtor()

    logger.info("Minimizing internals..")
    logger.info("verified_mcs.original_externals: " + verified_mcs.original_externals)
    val minimizer = new STSSchedMinimizer(mcs, verified_mcs, violation,
      removalStrategy, schedulerConfig,
      actorNameProps, initializationRoutine=initializationRoutine,
      preTest=preTest, postTest=postTest, stats=stats)
    return minimizer.minimize()
  }

  // Returns a new MCS, with Send contents shrinked as much as possible.
  // Pre: all Send() events have the same message contents.
  def shrinkSendContents(schedulerConfig: SchedulerConfig,
                         mcs: Seq[ExternalEvent],
                         verified_mcs: EventTrace,
                         actorNameProps: Seq[Tuple2[Props, String]],
                         violation: ViolationFingerprint,
                         stats: Option[MinimizationStats]=None)
       : Tuple2[MinimizationStats, Seq[ExternalEvent]] = {
    // Invariants (TODO(cs): specific to akka-raft?):
    //   - Require that all Send() events have the same message contents
    //   - Ensure that whenever a component is masked from one Send()'s
    //     message contents, all other Send()'s have the same component
    //     masked. i.e. throughout minimization, the first invariant holds!
    //   - (Definitely specific to akka-raft:) if an ActorRef component is
    //     removed, also remove the Start event for that node.

    // Pseudocode:
    // for component in firstSend.messageConstructor.getComponents:
    //   if (masking component still triggers bug):
    //     firstSend.maskComponent!

    logger.info("Shrinking Send contents..")
    val _stats = stats match {
      case Some(s) => s
      case None => new MinimizationStats
    }
    _stats.updateStrategy("ShrinkSendContents", "STSSched")

    val shrinkable_sends = mcs flatMap {
      case s @ Send(dst, ctor) =>
        if (!ctor.getComponents.isEmpty ) {
          Some(s)
        } else {
          None
        }
      case _ => None
    } toSeq

    if (shrinkable_sends.isEmpty) {
      logger.warn("No shrinkable sends")
      return ((_stats,mcs))
    }

    var components = shrinkable_sends.head.messageCtor.getComponents
    if (shrinkable_sends.forall(s => s.messageCtor.getComponents == components)) {
      logger.debug("shrinkable_sends: " + shrinkable_sends)
      throw new IllegalArgumentException("Not all shrinkable_sends the same")
    }

    def modifyMCS(mcs: Seq[ExternalEvent], maskedIndices: Set[Int]): Seq[ExternalEvent] = {
      // Also remove Start() events for masked actors
      val maskedActors = components.zipWithIndex.filter {
        case (e, i) => maskedIndices contains i
      }.map { case (e, i) => e.path.name }.toSet

      return mcs flatMap {
        case s @ Send(dst, ctor) =>
          val updated = Send(dst, ctor.maskComponents(maskedIndices))
          // Be careful to make Send ids the same.
          updated._id = s._id
          Some(updated)
        case s @ Start(_, name) =>
          if (maskedActors contains name) {
            None
          } else {
            Some(s)
          }
        case e => Some(e)
      }
    }

    var maskedIndices = Set[Int]()
    for (i <- 0 until components.size) {
      logger.info("Trying to remove component " + i + ":" + components(i))
      val modifiedMCS = modifyMCS(mcs, maskedIndices + i)
      testWithStsSched(schedulerConfig, modifiedMCS,
                       verified_mcs, actorNameProps, violation, _stats) match {
        case Some(trace) =>
          logger.info("Violation reproducable after removing component " + i)
          maskedIndices = maskedIndices + i
        case None =>
          logger.info("Violation not reproducable")
      }
    }

    logger.info("Was able to remove the following components: " + maskedIndices)
    val modified = modifyMCS(mcs, maskedIndices)
    return ((_stats, modified))
  }

  // Slight misnomer: stats as in "interesting numbers", not
  // MinimizationStatistics object.
  def printMinimizationStats(original_experiment_dir: String,
                             mcs_dir: String,
                             messageDeserializer: MessageDeserializer,
                             schedulerConfig: SchedulerConfig) {
    // Print:
    //  - number of original message deliveries
    //  - deliveries removed by provenance
    //  - number of deliveries pruned by minimizing external events
    //    (including internal deliveries that disappeared "by chance")
    //  - deliveries removed by internal minimization
    //  - final number of deliveries
    // TODO(cs): print how many replays went into each of these steps
    // TODO(cs): number of non-delivery external events that were pruned by minimizing?
    var deserializer = new ExperimentDeserializer(original_experiment_dir)
    val dummy_sched = new ReplayScheduler(schedulerConfig)
    Instrumenter().scheduler = dummy_sched
    dummy_sched.populateActorSystem(deserializer.get_actors)

    val origTrace = deserializer.get_events(messageDeserializer, Instrumenter().actorSystem)
    val provenanceTrace = deserializer.get_filtered_initial_trace()

    deserializer = new ExperimentDeserializer(mcs_dir)

    val mcsTrace = deserializer.get_events(messageDeserializer, Instrumenter().actorSystem)
    val intMinTrace = deserializer.get_events(
          messageDeserializer, Instrumenter().actorSystem,
          traceFile=ExperimentSerializer.minimizedInternalTrace)

    printMinimizationStats(origTrace, provenanceTrace, mcsTrace, intMinTrace,
      schedulerConfig.messageFingerprinter)

    dummy_sched.shutdown
  }

  def printMinimizationStats(
    origTrace: EventTrace, provenanceTrace: Option[Queue[Unique]],
    mcsTrace: EventTrace, intMinTrace: EventTrace,
    messageFingerprinter: FingerprintFactory,
    additionalNamedTraces:Seq[(String,EventTrace)]=Seq.empty) {

    def get_deliveries(trace: EventTrace) : Seq[MsgEvent] = {
      // Make sure not to count checkpoint and failure detector messages.
      // Also annotate Timers from other messages, by ensuring that the
      // "sender" field is "Timer"
      trace.filterFailureDetectorMessages.
            filterCheckpointMessages.flatMap {
        case m @ MsgEvent(s, r, msg) =>
          val fingerprint = messageFingerprinter.fingerprint(msg)
          if ((s == "deadLetters" || s == "Timer") && BaseFingerprinter.isFSMTimer(fingerprint)) {
            Some(MsgEvent("Timer", r, msg))
          } else {
            Some(MsgEvent(s, r, msg))
          }
        case t: TimerDelivery => Some(MsgEvent("Timer", t.receiver, t.fingerprint))
        case e => None
      }.toSeq
    }
    def count_externals(msgEvents: Seq[MsgEvent]): Int = {
      msgEvents flatMap {
        case m @ MsgEvent(_, _, _) =>
          if (EventTypes.isExternal(m)) {
            Some(m)
          } else {
            None
          }
        case _ => None
      } length
    }
    def count_timers(msgEvents: Seq[MsgEvent]): Int = {
      msgEvents flatMap {
        case m @ MsgEvent("Timer", _, _) => Some(m)
        case _ => None
      } length
    }

    class OrderedTracePrinter {
      // { (name, deliveries, externals, timers) }
      var traceStats : Seq[(String,Seq[MsgEvent],Int,Int)] = Seq.empty

      def appendTrace(name: String, deliveries:Seq[MsgEvent],
                      externalsUnchanged:Boolean=false) {
        val externals = if (externalsUnchanged)
          traceStats.last._3
          else  count_externals(deliveries)
        val timers = count_timers(deliveries)
        traceStats = traceStats.:+((name,deliveries,externals,timers))
      }

      // should only be invoked once
      def print {
        var prev: (String,Seq[MsgEvent],Int,Int) = null
        while (!traceStats.isEmpty) {
          val current = traceStats.head
          traceStats = traceStats.tail

          if (prev == null) {
            println(s"${current._1} message deliveries: ${current._2.size} (${current._3} externals, ${current._4} timers)")
          } else {
            println(s"Removed by ${current._1}: ${(prev._2.size - current._2.size)} (${(prev._3 - current._3)} externals, ${(prev._4 - current._4)} timers)")
            println(s"[After ${current._1}: ${current._2.size} (${current._3} externals, ${current._4} timers)]")
          }

          prev = current

          if (traceStats.isEmpty) {
            // TODO(cs): annotate which events are unignorable.
            println("Final messages delivered:") // w/o fingerints
            current._2 foreach { case e => println(e) }
          }
        }
      }
    }

    val printer = new OrderedTracePrinter
    printer.appendTrace("Original", get_deliveries(origTrace))

    // Assumes get_filtered_initial_trace only contains Unique(MsgEvent)s
    def getProvenanceDeliveries(trace: Queue[Unique]) : Seq[MsgEvent] = {
      trace.flatMap {
         case Unique(m @ MsgEvent(s,r,msg), id) =>
           if (MessageTypes.fromFailureDetector(msg) ||
               MessageTypes.fromCheckpointCollector(msg)) {
             None
           } else if (id == 0) {
             // Filter out root event
             None
           } else {
             val fingerprint = messageFingerprinter.fingerprint(msg)
             if ((s == "deadLetters" || s == "Timer") && BaseFingerprinter.isFSMTimer(fingerprint)) {
               Some(MsgEvent("Timer", r, msg))
             } else {
               Some(MsgEvent(s, r, msg))
             }
           }
         case e => throw new UnsupportedOperationException("Non-MsgEvent:" + e)
       }
     }

    provenanceTrace match {
      case Some(trace) =>
        printer.appendTrace("Provenance", getProvenanceDeliveries(trace),
          externalsUnchanged=true)
      case None =>
        Seq.empty
    }

    // Should be same actors, so no need to populateActorSystem
    printer.appendTrace("DDMin", get_deliveries(mcsTrace))
    printer.appendTrace("internal min", get_deliveries(intMinTrace))
    additionalNamedTraces.foreach {
      case (name,trace) => printer.appendTrace(name, get_deliveries(trace))
    }

    printer.print
  }

  def getDeliveries(trace: EventTrace): Seq[Event] = {
    trace flatMap {
      case m: MsgEvent => Some(m)
      case t: TimerDelivery => Some(t)
      case _ => None
    } toSeq
  }

  def getRawDeliveries(trace: EventTrace): Seq[Event] = {
    trace.events flatMap {
      case m: UniqueMsgEvent => Some(m)
      case t: UniqueTimerDelivery => Some(t)
      case _ => None
    } toSeq
  }

  def getFingerprintedDeliveries(trace: EventTrace,
                                 messageFingerprinter: FingerprintFactory):
                               Seq[(String,String,MessageFingerprint)] = {
    RunnerUtils.getDeliveries(trace) map {
      case MsgEvent(snd,rcv,msg) =>
        ((snd,rcv,messageFingerprinter.fingerprint(msg)))
      case TimerDelivery(snd,rcv,f) =>
        ((snd,rcv,f))
    } toSeq
  }

  def countMsgEvents(trace: Iterable[Event]) : Int = {
    return trace.filter {
      case m: MsgEvent => true
      case u: UniqueMsgEvent => true
      case t: TimerDelivery => true
      case u: UniqueTimerDelivery => true
      case _ => false
    } size
  }

  def printDeliveries(trace: EventTrace) {
    getDeliveries(trace) foreach { case e => println(e) }
  }

  def printMCS(mcs: Seq[ExternalEvent]) {
    println("----------")
    println("MCS: ")
    mcs foreach {
      case s @ Send(rcv, msgCtor) =>
        println(s.label + ":Send("+rcv+","+msgCtor()+")")
      case e => println(e.label + ":"+e.toString)
    }
    println("----------")
  }

  // Generate a log that can be fed into ShiViz!
  def visualizeDeliveries(trace: EventTrace, outputFile: String) {
    val writer = new PrintWriter(new File(outputFile))
    val logger = new VCLogger(writer)

    val id2delivery = new HashMap[Int, Event]
    trace.events.foreach {
      case u @ UniqueMsgEvent(m, id) =>
        id2delivery(id) = u
      case _ =>
    }

    writer.println("(?<clock>.*\\}) (?<host>[^:]*): (?<event>.*)")
    writer.println("")
    trace.events.foreach {
      case UniqueMsgSend(MsgSend(snd,rcv,msg), id) =>
        if ((id2delivery contains id) && snd != "deadLetters" && snd != "Timer") {
          logger.log(snd, "Sending to " + rcv + ": " + msg)
        }
      case UniqueMsgEvent(MsgEvent(snd,rcv,msg), id) =>
        if (snd == "deadLetters") {
          logger.log(rcv, "Received external message: " + msg)
        } else {
          logger.mergeVectorClocks(snd,rcv)
          logger.log(rcv, "Received message from " + snd + ": " + msg)
        }
      case UniqueTimerDelivery(TimerDelivery(snd,rcv,fingerprint), id) =>
        logger.log(rcv, "Received timer: " + fingerprint)
      case _ =>
    }
    writer.close
    println("ShiViz input at: " + outputFile)
  }

  /**
   * Make it easier to construct specifically delivery orders manually.
   *
   * Given:
   *  - An event trace to be twiddled with
   *  - A sequence of UniqueMsgSends.ids (the id value from the UniqueMsgSends
   *     contained in event trace).
   *
   * Return: a new event trace that has UniqueMsgEvents and
   * UniqueTimerDeliveries arranged in the order
   * specified by the given sequences of ids
   */
  // TODO(cs): allow caller to specify locations of external events. For now
  // just put them all at the front.
  // TODO(cs): use DepGraph ids rather than Unique ids, which are more finiky
  def reorderDeliveries(trace: EventTrace, ids: Seq[Int]): EventTrace = {
    val result = new SynchronizedQueue[Event]

    val id2send = new HashMap[Int, UniqueMsgSend]
    trace.events.foreach {
      case u @ UniqueMsgSend(MsgSend(snd, rcv, msg), id) =>
        if (snd == "Timer") {
          id2send(id) = UniqueMsgSend(MsgSend("deadLetters", rcv, msg), id)
        } else {
          id2send(id) = u
        }
      case _ =>
    }

    val id2delivery = new HashMap[Int, Event]
    trace.events.foreach {
      case u @ UniqueMsgEvent(m, id) =>
        id2delivery(id) = u
      case u @ UniqueTimerDelivery(t, id) =>
        id2delivery(id) = u
      case _ =>
    }

    // Put all SpawnEvents, external events at the beginning of the trace
    trace.events.foreach {
      case u @ UniqueMsgSend(m, id) =>
        if (EventTypes.isExternal(u)) {
          result += u
        }
      case u @ UniqueMsgEvent(m, id) =>
      case u @ UniqueTimerDelivery(t, id) =>
      case e => result += e
    }

    // Now append all the specified deliveries
    ids.foreach {
      case id =>
        if (id2delivery contains id) {
          result += id2delivery(id)
        } else {
          // fabricate a UniqueMsgEvent
          val msgSend = id2send(id).m
          result += UniqueMsgEvent(MsgEvent(
            msgSend.sender, msgSend.receiver, msgSend.msg), id)
        }
    }

    return new EventTrace(result, trace.original_externals)
  }
}

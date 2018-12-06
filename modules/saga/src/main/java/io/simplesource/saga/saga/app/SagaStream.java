package io.simplesource.saga.saga.app;

import io.simplesource.data.NonEmptyList;
import io.simplesource.data.Result;
import io.simplesource.data.Sequence;
import io.simplesource.kafka.internal.util.Tuple2;
import io.simplesource.saga.model.messages.SagaRequest;
import io.simplesource.saga.model.messages.SagaResponse;
import io.simplesource.saga.model.messages.SagaStateTransition;
import io.simplesource.saga.model.saga.ActionStatus;
import io.simplesource.saga.model.saga.Saga;
import io.simplesource.saga.model.saga.SagaError;
import io.simplesource.saga.model.saga.SagaStatus;
import io.simplesource.saga.model.serdes.SagaSerdes;
import lombok.Value;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final public class SagaStream {
    static Logger logger = LoggerFactory.getLogger(SagaStream.class);

    static <K, V> ForeachAction<K, V> logValues(String string) {
        return (k, v) -> logger.info("$prefix: {}={}", k.toString().substring(0, 6), v.toString());
    }

    //  def addSubTopology<A>(ctx: SagaContext<A>,
//                        sagaRequestStream: KStream<UUID, SagaRequest<A>>,
//                        stateTransitionStream: KStream<UUID, SagaStateTransition<A>>,
//                        stateStream: KStream<UUID, Saga<A>>,
//                        actionResponseStream: KStream<UUID, ActionResponse>): Unit = {
//
//    // create the state table from the state stream
//    val stateTable: KTable<UUID, Saga<A>> = createStateTable(ctx, stateStream)
//
//    // add stream transformations
//    val inputStateTransitions                = addInitialState(ctx, sagaRequestStream, stateTable)
//    val (requestTransitions, actionRequests) = addNextActions(ctx, stateStream)
//    val responseTransitions                  = addActionResponses(ctx, actionResponseStream)
//    val (sagaTransitions, sagaResponses)     = addSagaResponse(ctx, stateStream)
//    val sagaState                            = applyStateTransitions(ctx, stateTransitionStream, stateTable)
//
//    // publish to all the output topics
//    SagaProducer.actionRequests(ctx, actionRequests)
//    SagaProducer.sagaStateTransitions(ctx,
//                                      inputStateTransitions,
//                                      requestTransitions,
//                                      responseTransitions,
//                                      sagaTransitions)
//    SagaProducer.sagaState(ctx, sagaState)
//    SagaProducer.sagaResponses(ctx, sagaResponses)
//  }
    static <A> KTable<UUID, Saga<A>> createStateTable(SagaContext<A> ctx, KStream<UUID, Saga<A>> stateStream) {
        return stateStream.groupByKey().reduce(
                (s1, s2) -> (s1.sequence.getSeq() > s2.sequence.getSeq()) ? s1 : s2,
                Materialized.with(ctx.sSerdes.uuid(), ctx.sSerdes.state()));
    }

    static <A> KStream<UUID, Saga<A>> applyStateTransitions(SagaContext<A> ctx,
                                                           KStream<UUID, SagaStateTransition<A>> stateTransitionStream,
                                                           KStream<UUID, Saga<A>> stateStream) {
        SagaSerdes<A> sSerdes = ctx.sSerdes;

        Materialized<UUID, Saga<A>, KeyValueStore<Bytes, byte[]>> materialized = Materialized
                .<UUID, Saga<A>, KeyValueStore<Bytes, byte[]>>as("saga_state_aggregation")
                .withKeySerde(sSerdes.uuid())
                .withValueSerde(sSerdes.state());

    // TODO: remove the random UUIDs
    return stateTransitionStream
      .groupByKey(Serialized.with(sSerdes.uuid(), sSerdes.transition()))
      .aggregate(() -> new Saga<>(UUID.randomUUID(), new HashMap<>(), SagaStatus.NotStarted, Sequence.first()),
              (k, t, s) -> SagaUtils.applyTransition(t, s),
                          materialized)
            .toStream();
 }

 static <A>  KStream<UUID, SagaStateTransition<A>> addInitialState(SagaContext<A> ctx,
                                 KStream<UUID, SagaRequest<A>> sagaRequestStream,
                                 KTable<UUID, Saga<A>> stateTable) {
     SagaSerdes<A> sSerdes = ctx.sSerdes;
     KStream<UUID, Tuple2<SagaRequest<A>, Boolean>> newRequestStream = sagaRequestStream.leftJoin(
             stateTable,
             (v1, v2) -> Tuple2.of(v1, v2 == null),
             Joined.with(sSerdes.uuid(), sSerdes.request(), sSerdes.state()))
             .filter((k, tuple) -> tuple.v2());

     return newRequestStream.mapValues((k, v) -> new SagaStateTransition.SetInitialState<>(v.v1().initialState));
}

@Value
static class StatusWithError {
    Sequence sequence;
        SagaStatus status;
        Optional<SagaError> error;

    static Optional<StatusWithError> of(Sequence sequence, SagaStatus status) {
        return Optional.of(new StatusWithError(sequence, status, Optional.empty()));
    }

    static Optional<StatusWithError> of(Sequence sequence, SagaStatus status, SagaError error) {
        return Optional.of(new StatusWithError(sequence, status, Optional.of(error)));
    }
}

static <A> Tuple2<KStream<UUID, SagaStateTransition<A>>, KStream<UUID, SagaResponse>> addSagaResponse(SagaContext<A> ctx,
                                                                                                      KStream<UUID, Saga<A>> sagaState) {
   KStream<UUID, StatusWithError> statusWithError =
            sagaState.mapValues((k, state) -> {

        if (state.status == SagaStatus.InProgress && SagaUtils.sagaCompleted(state))
            return StatusWithError.of(state.sequence, SagaStatus.Completed);
        if (state.status == SagaStatus.InProgress && SagaUtils.sagaInFailure(state))
            return StatusWithError.of(state.sequence, SagaStatus.InFailure);
        if ((state.status == SagaStatus.InFailure || state.status == SagaStatus.InProgress) &&
                   SagaUtils.sagaFailed(state)) {
            Set<String> errors = state.actions.values().stream()
                    .filter(action -> action.status == ActionStatus.Failed)
                    .map(action -> action.error.map(e -> e.messages.stream()).orElse(Stream.empty()))
                    .flatMap(x -> x).collect(Collectors.toSet());

            NonEmptyList<String> errorList = NonEmptyList.fromList(new ArrayList<>(errors));
            return StatusWithError.of(state.sequence, SagaStatus.Failed, new SagaError(errorList));
        }
        return Optional.<StatusWithError>empty();
    }).filter((k, sWithE) -> sWithE.isPresent())
           .mapValues((k, v) -> v.get());

    KStream<UUID, SagaStateTransition<A>> stateTransition = statusWithError.mapValues((sagaId, someStatus) ->
            new SagaStateTransition.SagaStatusChanged<>(sagaId, someStatus.status, someStatus.error));

    KStream<UUID, SagaResponse>  sagaResponse =
            statusWithError
        .mapValues((sagaId, sWithE) -> {
            Optional<Result<SagaError, Sequence>> result;
            SagaStatus status = sWithE.status;
            if (status == SagaStatus.Completed)
                return Optional.of(Result.success(sWithE.sequence));
            if (status == SagaStatus.Failed)
                return Optional.of(Result.failure(sWithE.error));
            return Optional.empty();




            {
            case SagaStatus.Completed => Some(Right(()))
            case SagaStatus.Failed(error) =>
              Some(Left(error))
            case _ => None
          }
          result.map(r => SagaResponse(sagaId, r))
        })
        ._collect { case Some(x) => x }


    return Tuple2.of(stateTransition, null);
}


//
//  private def addSagaResponse<A>(ctx: SagaContext<A>, sagaState: KStream<UUID, Saga<A>>)
//    : (KStream<UUID, SagaStateTransition<A>>, KStream<UUID, SagaResponse>) = {
//    // get the next actions from the state updates

//
//    val stateTransition =
//      sagaStateChanges._mapValuesWithKey<SagaStateTransition<A>>((sagaId, someStatus) =>
//        SagaStatusChanged<A>(sagaId, someStatus.get))
//
//    val sagaResponse: KStream<UUID, SagaResponse> =
//      sagaStateChanges
//        ._mapValuesWithKey((sagaId, someStatus) => {
//          val result: Option<Either<SagaError, Unit>> = someStatus.get match {
//            case SagaStatus.Completed => Some(Right(()))
//            case SagaStatus.Failed(error) =>
//              Some(Left(error))
//            case _ => None
//          }
//          result.map(r => SagaResponse(sagaId, r))
//        })
//        ._collect { case Some(x) => x }
//
//    (stateTransition, sagaResponse)
//  }
//
//  def addNextActions<A>(ctx: SagaContext<A>, sagaState: KStream<UUID, Saga<A>>)
//    : (KStream<UUID, SagaStateTransition<A>>, KStream<UUID, ActionRequest<A>>) = {
//    // get the next actions from the state updates
//    val nextActionsListStream: KStream<UUID, List<SagaActionExecution<A>>> =
//      sagaState._mapValues(state => SagaUtils.getNextActions(state))
//
//    val nextActionsStream: KStream<UUID, SagaActionExecution<A>> =
//      nextActionsListStream._flatMapValues<SagaActionExecution<A>>(identity)
//
//    val stateUpdateNewActions: KStream<UUID, SagaStateTransition<A>> =
//      nextActionsListStream
//        ._filter(_.nonEmpty)
//        ._mapValuesWithKey<SagaStateTransition<A>>((k, actions) => {
//          val transitions = actions.map { action =>
//            SagaActionStatusChanged<A>(sagaId = k, actionId = action.actionId, actionStatus = action.status)
//          }
//          TransitionList<A>(transitions)
//        })
//        .peek(logValues<UUID, SagaStateTransition<A>>("stateUpdateNewActions"))
//
//    val actionRequests: KStream<UUID, ActionRequest<A>> = {
//      nextActionsStream
//        ._filter(_.command.isDefined)
//        ._mapValuesWithKey<ActionRequest<A>>(
//          (k, ae) =>
//            ActionRequest<A>(sagaId = k,
//                             actionId = ae.actionId,
//                             actionCommand = ae.command.get,
//                             actionType = ae.actionType))
//    }.peek(logValues<UUID, ActionRequest<A>>("actionRequests"))
//
//    (stateUpdateNewActions, actionRequests)
//  }
//
//  def addActionResponses<A>(
//      ctx: SagaContext<A>,
//      actionResponseStream: KStream<UUID, ActionResponse>): KStream<UUID, SagaStateTransition<A>> = {
//
//    actionResponseStream
//      ._mapValuesWithKey<SagaStateTransition<A>>((sagaId, response) => {
//        val newStatus =
//          response.result.fold(error => ActionStatus.Failed(error), _ => ActionStatus.Completed)
//        SagaActionStatusChanged<A>(sagaId = sagaId, actionId = response.actionId, actionStatus = newStatus)
//      })
//      .peek(logValues<UUID, SagaStateTransition<A>>("stateTransitionsActionResponse"))
//
//  }
//}

    }

//package io.simplesource.saga.saga.app
//
//import java.util.UUID
//
//import cats.data.NonEmptyList
//import model.messages._
//import model.saga._
//import org.apache.kafka.common.utils.Bytes
//import org.apache.kafka.streams.kstream._
//import org.apache.kafka.streams.state.KeyValueStore
//import org.slf4j.LoggerFactory
//import shared.streams.syntax._
//
//object SagaStream {
//  private val logger = LoggerFactory.getLogger("SagaStream")
//
//  private def logValues<K, V>(prefix: String): ForeachAction<K, V> =
//    (k: K, v: V) => logger.info(s"$prefix: ${k.toString.take(6)}=$v")
//
//  def addSubTopology<A>(ctx: SagaContext<A>,
//                        sagaRequestStream: KStream<UUID, SagaRequest<A>>,
//                        stateTransitionStream: KStream<UUID, SagaStateTransition<A>>,
//                        stateStream: KStream<UUID, Saga<A>>,
//                        actionResponseStream: KStream<UUID, ActionResponse>): Unit = {
//
//    // create the state table from the state stream
//    val stateTable: KTable<UUID, Saga<A>> = createStateTable(ctx, stateStream)
//
//    // add stream transformations
//    val inputStateTransitions                = addInitialState(ctx, sagaRequestStream, stateTable)
//    val (requestTransitions, actionRequests) = addNextActions(ctx, stateStream)
//    val responseTransitions                  = addActionResponses(ctx, actionResponseStream)
//    val (sagaTransitions, sagaResponses)     = addSagaResponse(ctx, stateStream)
//    val sagaState                            = applyStateTransitions(ctx, stateTransitionStream, stateTable)
//
//    // publish to all the output topics
//    SagaProducer.actionRequests(ctx, actionRequests)
//    SagaProducer.sagaStateTransitions(ctx,
//                                      inputStateTransitions,
//                                      requestTransitions,
//                                      responseTransitions,
//                                      sagaTransitions)
//    SagaProducer.sagaState(ctx, sagaState)
//    SagaProducer.sagaResponses(ctx, sagaResponses)
//  }
//
//  private def createStateTable<A>(ctx: SagaContext<A>,
//                                  stateStream: KStream<UUID, Saga<A>>): KTable<UUID, Saga<A>> = {
//    val reducer: Reducer<Saga<A>> = (s1, s2) => if (s1.sequence > s2.sequence) s1 else s2
//    stateStream.groupByKey().reduce(reducer, Materialized.`with`(ctx.sSerdes.uuid, ctx.sSerdes.state))
//  }
//
//  private def applyStateTransitions<A>(ctx: SagaContext<A>,
//                                       stateTransitionStream: KStream<UUID, SagaStateTransition<A>>,
//                                       stateTable: KTable<UUID, Saga<A>>): KStream<UUID, Saga<A>> = {
//    val sSerdes = ctx.sSerdes
//
//    val aggregator: Aggregator<UUID, SagaStateTransition<A>, Saga<A>> = (_, t, s) =>
//      SagaUtils.applyTransition(t, s)
//
//    val materialized: Materialized<UUID, Saga<A>, KeyValueStore<Bytes, Array<Byte>>> = Materialized
//      .as<UUID, Saga<A>, KeyValueStore<Bytes, Array<Byte>>>("saga_state_aggregation")
//      .withKeySerde(ctx.sSerdes.uuid)
//      .withValueSerde(sSerdes.state)
//
//    stateTransitionStream
//      .groupByKey(Serialized.`with`(sSerdes.uuid, sSerdes.transition))
//      .aggregate<Saga<A>>(() => Saga<A>(Map.empty, SagaStatus.NotStarted, sequence = -1),
//                          aggregator,
//                          materialized)
//      .toStream
//  }
//
//  private def addInitialState<A>(ctx: SagaContext<A>,
//                                 sagaRequestStream: KStream<UUID, SagaRequest<A>>,
//                                 stateTable: KTable<UUID, Saga<A>>): KStream<UUID, SagaStateTransition<A>> = {
//    val sSerdes = ctx.sSerdes
//    val newRequestStream = {
//      val joiner: ValueJoiner<SagaRequest<A>, Saga<A>, (SagaRequest<A>, Boolean)> =
//        (v1: SagaRequest<A>, v2: Saga<A>) => (v1, v2 == null)
//
//      sagaRequestStream
//        .leftJoin<Saga<A>, (SagaRequest<A>, Boolean)>(
//          stateTable,
//          joiner,
//          Joined.`with`(sSerdes.uuid, sSerdes.request, sSerdes.state)
//        )
//        ._collect { case (request, true) => request }
//    }
//
//    newRequestStream._mapValues<SagaStateTransition<A>> { request =>
//      SetInitialState<A>(request.initialState)
//    }
//  }
//
//  private def addSagaResponse<A>(ctx: SagaContext<A>, sagaState: KStream<UUID, Saga<A>>)
//    : (KStream<UUID, SagaStateTransition<A>>, KStream<UUID, SagaResponse>) = {
//    // get the next actions from the state updates
//    val sagaStateChanges =
//      sagaState
//        ._mapValues(state => {
//          // TODO: improve this logic
//          if (state.status == SagaStatus.InProgress && SagaUtils.sagaCompleted(state))
//            Some(SagaStatus.Completed)
//          else if (state.status == SagaStatus.InProgress && SagaUtils.sagaInFailure(state))
//            Some(SagaStatus.InFailure)
//          else if ((state.status == SagaStatus.InFailure || state.status == SagaStatus.InProgress) &&
//                   SagaUtils.sagaFailed(state)) {
//            val errors = state.actions.values
//              .collect {
//                case SagaAction(_, _, _, _, _, ActionStatus.Failed(error)) => error.messages.toList
//              }
//              .flatten
//              .toList
//              .distinct
//            val errorList =
//              NonEmptyList.fromList(errors).getOrElse(NonEmptyList.one("Unexpected Saga Error."))
//            Some(SagaStatus.Failed(SagaError(errorList)))
//          } else
//            None
//        })
//        ._filter(_.isDefined)
//
//    val stateTransition =
//      sagaStateChanges._mapValuesWithKey<SagaStateTransition<A>>((sagaId, someStatus) =>
//        SagaStatusChanged<A>(sagaId, someStatus.get))
//
//    val sagaResponse: KStream<UUID, SagaResponse> =
//      sagaStateChanges
//        ._mapValuesWithKey((sagaId, someStatus) => {
//          val result: Option<Either<SagaError, Unit>> = someStatus.get match {
//            case SagaStatus.Completed => Some(Right(()))
//            case SagaStatus.Failed(error) =>
//              Some(Left(error))
//            case _ => None
//          }
//          result.map(r => SagaResponse(sagaId, r))
//        })
//        ._collect { case Some(x) => x }
//
//    (stateTransition, sagaResponse)
//  }
//
//  def addNextActions<A>(ctx: SagaContext<A>, sagaState: KStream<UUID, Saga<A>>)
//    : (KStream<UUID, SagaStateTransition<A>>, KStream<UUID, ActionRequest<A>>) = {
//    // get the next actions from the state updates
//    val nextActionsListStream: KStream<UUID, List<SagaActionExecution<A>>> =
//      sagaState._mapValues(state => SagaUtils.getNextActions(state))
//
//    val nextActionsStream: KStream<UUID, SagaActionExecution<A>> =
//      nextActionsListStream._flatMapValues<SagaActionExecution<A>>(identity)
//
//    val stateUpdateNewActions: KStream<UUID, SagaStateTransition<A>> =
//      nextActionsListStream
//        ._filter(_.nonEmpty)
//        ._mapValuesWithKey<SagaStateTransition<A>>((k, actions) => {
//          val transitions = actions.map { action =>
//            SagaActionStatusChanged<A>(sagaId = k, actionId = action.actionId, actionStatus = action.status)
//          }
//          TransitionList<A>(transitions)
//        })
//        .peek(logValues<UUID, SagaStateTransition<A>>("stateUpdateNewActions"))
//
//    val actionRequests: KStream<UUID, ActionRequest<A>> = {
//      nextActionsStream
//        ._filter(_.command.isDefined)
//        ._mapValuesWithKey<ActionRequest<A>>(
//          (k, ae) =>
//            ActionRequest<A>(sagaId = k,
//                             actionId = ae.actionId,
//                             actionCommand = ae.command.get,
//                             actionType = ae.actionType))
//    }.peek(logValues<UUID, ActionRequest<A>>("actionRequests"))
//
//    (stateUpdateNewActions, actionRequests)
//  }
//
//  def addActionResponses<A>(
//      ctx: SagaContext<A>,
//      actionResponseStream: KStream<UUID, ActionResponse>): KStream<UUID, SagaStateTransition<A>> = {
//
//    actionResponseStream
//      ._mapValuesWithKey<SagaStateTransition<A>>((sagaId, response) => {
//        val newStatus =
//          response.result.fold(error => ActionStatus.Failed(error), _ => ActionStatus.Completed)
//        SagaActionStatusChanged<A>(sagaId = sagaId, actionId = response.actionId, actionStatus = newStatus)
//      })
//      .peek(logValues<UUID, SagaStateTransition<A>>("stateTransitionsActionResponse"))
//
//  }
//}


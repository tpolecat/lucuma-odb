// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.service

import cats.Eq
import cats.Order.catsKernelOrderingForOrder
import cats.syntax.apply.*
import cats.syntax.functor.*
import cats.syntax.option.*
import cats.syntax.order.*
import fs2.Pipe
import fs2.Stream
import lucuma.core.enums.ChargeClass
import lucuma.core.model.Visit
import lucuma.core.model.sequence.Atom
import lucuma.core.model.sequence.CategorizedTime
import lucuma.core.util.Timestamp
import lucuma.core.util.TimestampInterval

import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet

/**
 * TimeAccountingState tracks time intervals associated with a visit and
 * potentially a step.  Intervals are not overlapping.
 */
sealed class TimeAccountingState private (val toMap: SortedMap[TimestampInterval, TimeAccounting.Context]) {

  import TimeAccountingState.Empty

  /**
   * Timestamp of the start of the first interval in the state, if any.
   */
  def start: Option[Timestamp] =
    toMap.headOption.map(_._1.start)

  /**
   * Timestamp of the end of the last interval in the state, if any.
   */
  def end: Option[Timestamp] =
    toMap.lastOption.map(_._1.end)

  def toList: List[(TimestampInterval, TimeAccounting.Context)] =
    toMap.toList

  def isEmpty: Boolean =
    toMap.isEmpty

  def nonEmpty: Boolean =
    toMap.nonEmpty

  def contains(t: Timestamp): Boolean = {
    val i = TimestampInterval.empty(t)
    toMap.maxBefore(i).exists(_._1.contains(t)) ||
      toMap.minAfter(i).exists(_._1.contains(t))
  }

  /**
   * Calculates the time accounting charge associated with this state.
   */
  def charge: CategorizedTime =
    toMap.foldLeft(CategorizedTime.Zero) { case (c, (n, x)) =>
      c.sumCharge(x.chargeClass, n.boundedTimeSpan)
    }

  /**
   * Looks up the context at this given `Timestamp`, assuming it is covered by
   * the state.
   */
  def entryAt(t: Timestamp): Option[(TimestampInterval, TimeAccounting.Context)] =
    toMap
      .maxBefore(TimestampInterval.empty(t))
      .filter { case (interval, _) => interval.contains(t) }
      .orElse(
        // if t happens to be the start of an interval, `[t, t)` will
        // sort before it and min "after" will start at `t`.
        toMap
          .minAfter(TimestampInterval.empty(t))
          .filter { case (interval, _) => interval.contains(t) }
      )

  /**
   * Looks up the context at this given `Timestamp`, assuming it is covered by
   * the state.
   */
  def contextAt(t: Timestamp): Option[TimeAccounting.Context] =
    entryAt(t).map(_._2)

  private def mod(f: SortedMap[TimestampInterval, TimeAccounting.Context] => SortedMap[TimestampInterval, TimeAccounting.Context]): TimeAccountingState =
    new TimeAccountingState(f(toMap))

  /**
   * Extracts the portion of the state that happens before the given timestamp
   * (if any).  If the timestamp occurs in the middle of an interval, it will
   * be split such that the remaining portion starting at `t` is excluded.
   */
  def until(t: Timestamp): TimeAccountingState =
    mod(
      _.maxBefore(TimestampInterval.empty(t))
       .fold(Empty.toMap) { (k, v) =>
         if (k.contains(t)) toMap.rangeUntil(k) ++ k.minus(TimestampInterval.from(t)).tupleRight(v)
         else toMap.rangeTo(k)
       }
    )

  /**
   * Extracts the portion of the state that happens on or after the given
   * timestamp (if any).  If the timestamp occurs in the middle of an interval,
   * it will be split such that the initial portion until `t` is excluded.
   */
  def from(t: Timestamp): TimeAccountingState =
    mod(
      _.maxBefore(TimestampInterval.empty(t))
       .fold(toMap) { (k, v) =>
         val tail = toMap.rangeFrom(TimestampInterval.empty(t))
         if (k.contains(t)) tail ++ k.minus(TimestampInterval.until(t)).tupleRight(v)
         else tail
       }
    )

  /**
   * Extracts the portion of the state that intersects with the given interval
   * (if any).  If `interval` partially overlaps one or more intervals in the
   * state, they will be split as appropriate leaving only the portion that
   * falls within `interval` bounds.
   */
  def between(interval: TimestampInterval): TimeAccountingState =
    if (interval.isEmpty) Empty else from(interval.start).until(interval.end)

  /**
   * Extracts the state that does not intersect with `interval` (if any).  If
   * `interval` partially overlaps one or more intervals in the state, they will
   * be split as appropriate leaving only the portion that does not fall within
   * `interval` bounds.
   */
  def excluding(interval: TimestampInterval): TimeAccountingState =
    if (interval.isEmpty) this
    else new TimeAccountingState(until(interval.start).toMap ++ from(interval.end).toMap)

  /**
   * Calculates the set of `Atom.Id` associated with intervals in this state.
   */
  def allAtoms: SortedSet[Atom.Id] =
    toMap.foldLeft(SortedSet.empty[Atom.Id]) { case (atoms, (_, ctx)) =>
      ctx.step.fold(atoms) { stepContext => atoms + stepContext.atomId }
    }

  /**
   * Returns the set of `Atom.Id` that are associated with state intervals which
   * intersect with `interval`.
   */
  def atomsIntersecting(interval: TimestampInterval): SortedSet[Atom.Id] =
    if (interval.nonEmpty) between(interval).allAtoms
    else SortedSet.from(contextAt(interval.start).flatMap(_.step).map(_.atomId))

  /**
   * The minimal interval containing all the given atoms, if any.
   */
  def intervalContaining(atoms: Set[Atom.Id]): Option[TimestampInterval] =
    toMap.foldLeft(none[TimestampInterval]) { case (result, (interval, ctx)) =>
      if (!ctx.step.map(_.atomId).exists(atoms)) result
      else result.fold(interval.some)(_.span(interval).some)
    }

  /**
   * Partitions the state into two, the part included in `interval` and the
   * part excluding `interval`.
   *
   * @return a tuple containing the portion of state that intersects with
   *         interval and the portion that does not
   */
  def partitionOnInterval(interval: TimestampInterval): (TimeAccountingState, TimeAccountingState) =
    (between(interval), excluding(interval))

  /**
   * Partitions the state into two but avoiding the spliting of intervals
   * associated with atoms.  That is, if an interval is associated with an atom
   * and intersects `interval`, it is included in its entirety.  Essentially
   * `interval` is stretched to include any intervals with atoms it intersects.
   *
   * @return a tuple containing the portion of state that intersects with
   *         `interval` (keeping atoms together) and the portion that does
   *         intersect
   */
  def partitionOnAtomBoundary(interval: TimestampInterval): (TimeAccountingState, TimeAccountingState) = {
    val intervalʹ = intervalContaining(atomsIntersecting(interval)).getOrElse(interval).span(interval)
    (between(intervalʹ), excluding(intervalʹ))
  }

  /**
   * Partitions the state into two based on whether interval is associated with
   * the provided `atom`.
   *
   * @return a tuple containing the portion of the state associated with `atom`
   *         and the portion not associated with `atom`
   */
  def partitionOnAtom(atom: Atom.Id): (TimeAccountingState, TimeAccountingState) = {
    val (in, out) = toMap.foldLeft((Empty.toMap, Empty.toMap)) { case ((in, out), (interval, ctx)) =>
      if (ctx.step.exists(_.atomId === atom)) (in + (interval -> ctx), out)
      else (in, out + (interval -> ctx))
    }
    (new TimeAccountingState(in), new TimeAccountingState(out))
  }

  override def equals(that: Any): Boolean =
    that match {
      case s: TimeAccountingState => toMap === s.toMap
      case _                      => false
    }

  override def hashCode: Int =
    toMap.hashCode() * 31

  override def toString: String =
    s"TimeAccountingState(${toMap.mkString(", ")})"
}

object TimeAccountingState {

  val Empty: TimeAccountingState =
    new TimeAccountingState(SortedMap.empty)

  given Eq[TimeAccountingState] =
    Eq.by(_.toMap)

  /**
   * Creates a TimeAccountingState from a sequence of events, assuming they are
   * sorted.
   */
  def unsafeFromEvents(
    chargeClass: ChargeClass,
    visitId:     Visit.Id,
    events:      Seq[TimeAccounting.Event]
  ): TimeAccountingState =
    Stream
      .emits(events)
      .through(eventStreamPipe(chargeClass, visitId))
      .toList
      .head

  /**
   * A pipe that processes the event stream into a single-element stream
   * containing the corresponding `TimeAccountingState`.
   */
  def eventStreamPipe[F[_]](
    chargeClass: ChargeClass,
    visitId: Visit.Id
  ): Pipe[F, TimeAccounting.Event, TimeAccountingState] = {

    // The events must be presented in order or this won't work.
    val validateSortOrder: Pipe[F, TimeAccounting.Event, TimeAccounting.Event] =
      _.zipWithNext
       .flatMap {
         case (cur, Some(next)) if (cur.timestamp > next.timestamp) => throw new RuntimeException("Events out of order!")
         case (cur, _)                                              => Stream.emit(cur)
       }

    // Pipe that turns time accounting events into interval -> context pairs
    // so that they can be used to create TimeAccountingState.  We merge all
    // the adjacent ones with the same context into a single entry covering
    // the entire time.
    val entriesPipe: Pipe[F, TimeAccounting.Event, (TimestampInterval, TimeAccounting.Context)] =
      _.groupAdjacentBy(_.context)
       .map { case (ctx, events) =>
         val head     = events.head.map(_.timestamp)
         val last     = events.last.map(_.timestamp)
         val interval =
           (head, last).mapN(TimestampInterval.between)
                       .getOrElse(sys.error("Stream.groupAdjacentBy produced an empty Chunk!"))

         interval -> ctx
       }.filter { case (interval, _) => interval.nonEmpty }

    // Pipe that fills in the gaps between steps with an interval -> context
    // pair (albeit with a None step context).
    val contiguousPipe: Pipe[F, (TimestampInterval, TimeAccounting.Context), (TimestampInterval, TimeAccounting.Context)] =
      _.zipWithNext
       .flatMap {
         case ((interval0, ctx0), None)                  =>
           Stream.emit(interval0 -> ctx0)
         case ((interval0, ctx0), Some(interval1, ctx1)) =>
           if (interval0.abuts(interval1)) Stream.emit(interval0 -> ctx0)
           else Stream(
             interval0 -> ctx0,
             TimestampInterval.between(interval0.end, interval1.start) -> TimeAccounting.Context(visitId, chargeClass, None)
           )
       }

    _.through(validateSortOrder)
     .through(entriesPipe)
     .through(contiguousPipe)
     .fold(Empty.toMap) { case (state, (interval, ctx)) =>
       state + (interval -> ctx)
     }
     .map(new TimeAccountingState(_))

  }
}

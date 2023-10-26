// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package mapping

import cats.syntax.option.*
import grackle.Cursor
import grackle.Predicate
import grackle.Predicate.Const
import grackle.Predicate.Eql
import grackle.Result
import grackle.Type
import grackle.TypeRef
import lucuma.core.enums.Instrument
import lucuma.core.model.User

import table.AtomRecordTable
import table.StepRecordTable
import table.VisitTable

trait AtomRecordMapping[F[_]] extends AtomRecordTable[F]
                                 with StepRecordTable[F]
                                 with VisitTable[F] {
  def user: User

  lazy val AtomRecordMapping: ObjectMapping =
    SqlInterfaceMapping(
      tpe           = AtomRecordType,
      discriminator = atomRecordTypeDiscriminator,
      fieldMappings = List(
        SqlField("id",           AtomRecordTable.Id, key = true),
        SqlField("instrument",   AtomRecordTable.Instrument, discriminator = true),
        SqlObject("visit",       Join(AtomRecordTable.VisitId, VisitTable.Id)),
        SqlField("created",      AtomRecordTable.Created),
        SqlField("sequenceType", AtomRecordTable.SequenceType),
        SqlField("stepCount",    AtomRecordTable.StepCount),

        // TBD: update to query with offset and limit, step record interface, etc.
        SqlObject("steps",       Join(AtomRecordTable.Id, StepRecordTable.AtomId))
      )
    )

  private lazy val atomRecordTypeDiscriminator: SqlDiscriminator =
    new SqlDiscriminator {
      override def discriminate(c: Cursor): Result[Type] =
        c.fieldAs[Instrument]("instrument").flatMap {
          case Instrument.GmosNorth => Result(GmosNorthAtomRecordType)
          case Instrument.GmosSouth => Result(GmosSouthAtomRecordType)
          case inst                 => Result.failure(s"No AtomRecord implementation for ${inst.shortName}")
        }

      private def mkPredicate(tpe: Instrument): Option[Predicate] =
        Eql(InstrumentType / "instrument", Const(tpe)).some

      override def narrowPredicate(tpe: Type): Option[Predicate] =
        tpe match {
          case GmosNorthAtomRecordType => mkPredicate(Instrument.GmosNorth)
          case GmosSouthAtomRecordType => mkPredicate(Instrument.GmosSouth)
          case _                       => none
        }
    }

  lazy val GmosNorthAtomRecordMapping: ObjectMapping =
    ObjectMapping(
      tpe = GmosNorthAtomRecordType,
      fieldMappings = List(
        SqlField("id", AtomRecordTable.Id, key = true)
      )
    )

  lazy val GmosSouthAtomRecordMapping: ObjectMapping =
    ObjectMapping(
      tpe = GmosSouthAtomRecordType,
      fieldMappings = List(
        SqlField("id", AtomRecordTable.Id, key = true)
      )
    )

}
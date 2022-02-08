package lucuma.odb.graphql
package snippet

import cats.effect.MonadCancelThrow
import cats.effect.kernel.Resource
import cats.syntax.all._
import edu.gemini.grackle.Cursor.Env
import edu.gemini.grackle.Path.UniquePath
import edu.gemini.grackle.Predicate
import edu.gemini.grackle.Predicate._
import edu.gemini.grackle.Query
import edu.gemini.grackle.Query._
import edu.gemini.grackle.Result
import edu.gemini.grackle.TypeRef
import edu.gemini.grackle.Value._
import edu.gemini.grackle.skunk.SkunkMapping
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import lucuma.core.model
import lucuma.core.model.Access._
import lucuma.core.model.User
import lucuma.odb.data._
import lucuma.odb.data.ProgramUserRole
import lucuma.odb.graphql.util.Bindings._
import lucuma.odb.graphql.util._
import lucuma.odb.service.ProgramService
import lucuma.odb.util.Codecs._
import skunk.Session

object ProgramSnippet {

  val schema = unsafeLoadSchema(this)

  // The types that we're going to map.
  val QueryType           = schema.ref("Query")
  val MutationType        = schema.ref("Mutation")
  val ProgramType         = schema.ref("Program")
  val ProgramUserType     = schema.ref("ProgramUser")
  val ProgramUserRoleType = schema.ref("ProgramUserRole")
  val ProgramIdType       = schema.ref("ProgramId")
  val UserIdType          = schema.ref("UserId")

  def apply[F[_]: MonadCancelThrow](
    m: SnippetMapping[F] with SkunkMapping[F],
    sessionPool: Resource[F, Session[F]],
    user: User
  ): m.Snippet = {

    import m.{ TableDef, ObjectMapping, Snippet, SqlRoot, SqlField, SqlObject, Join, Mutation, LeafMapping }

    val pool = sessionPool.map(ProgramService.fromSession(_))

    // Column references for our mapping.
    object Program extends TableDef("t_program") {
      val Id        = col("c_program_id", program_id)
      val Existence = col("c_existence", existence)
      val Name      = col("c_name", text_nonempty.opt)
    }
    object ProgramUser extends TableDef("t_program_user") {
      val ProgramId = col("c_program_id", program_id)
      val UserId    = col("c_user_id", user_id)
      val Role      = col("c_role", program_user_role)
    }
    object User extends TableDef("t_user") {
      val Id = col("c_user_id", user_id)
    }

    // Predicates we use our elaborator.
    object Predicates {

      def includeDeleted(b: Boolean): Predicate =
        if (b) True else Eql(UniquePath(List("existence")), Const[Existence](Existence.Present))

      def hasProgramId(pid: model.Program.Id): Predicate =
        Eql(UniquePath(List("id")), Const(pid))

      def hasProgramId(pids: List[model.Program.Id]): Predicate =
        In(UniquePath(List("id")), pids)

      def hasProgramId(pids: Option[List[model.Program.Id]]): Predicate =
        pids.fold[Predicate](True)(hasProgramId)

      def isVisibleTo(user: model.User): Predicate =
        user.role.access match {
          // TODO: handle NGO case
          case Guest | Pi    | Ngo     => Eql(UniquePath(List("users", "userId")), Const(user.id))
          case Staff | Admin | Service => True
        }

    }

    // TODO: upstream this
    implicit class MutationCompanionOps(self: Mutation.type) {
      // A mutation that yields a single result and doesn't change the environment
      def simple(f: (Query, Env) => F[Result[Query]]): Mutation =
        Mutation((q, e) => Stream.eval(f(q, e).map(_.map((_, e)))))
    }

    // Mutations for our mapping
    def createProgram: Mutation =
      Mutation.simple { (child, env) =>
        env.get[Option[NonEmptyString]]("name").map { name =>
          pool.use(_.insertProgram(name, user.id)).map { id =>
            Result[Query](
              Unique(
                Filter(
                  Predicates.hasProgramId(id), // no further filtering needed; we know it's visible to `user`
                  child
                )
              )
            )
         }
        } getOrElse Result.failure(s"Implementation error: expected 'name' in $env.").pure[F].widen
      }

    def updateProgram: Mutation =
      Mutation.simple { (child, env) =>
        ( env.get[model.Program.Id]("programId"),
          env.get[Option[Existence]]("existence"),
          env.get[Option[Option[NonEmptyString]]]("name")
        ).mapN { (programId, existence, name) =>
          pool.use(_.updateProgram(programId, existence, name, user)).map {
            case UpdateResult.NothingToBeDone => Result.failure("No updates specified.")
            case UpdateResult.NoSuchObject    => Result.failure(s"Program $programId does not exist or is not editable by user ${user.id}.")
            case UpdateResult.Success(id)     =>
              Result[Query](
                Unique(
                  Filter(
                    Predicates.hasProgramId(id), // no further filtering needed; we know it's editable by `user` and we want to see it even if it was deleted
                    child
                  )
                )
              )
          }
        } getOrElse Result.failure(s"Implementation error: expected 'programId', 'existence', and 'name' in $env.").pure[F].widen
      }

    // Our mapping, finally.
    val typeMappings =
      List(
        ObjectMapping(
          tpe = QueryType,
          fieldMappings = List(
            SqlRoot("programs"),
            SqlRoot("program"),
          )
        ),
        ObjectMapping(
          tpe = MutationType,
          fieldMappings = List(
            SqlRoot("createProgram", mutation = createProgram),
            SqlRoot("updateProgram", mutation = updateProgram),
          )
        ),
        ObjectMapping(
          tpe = ProgramType,
          fieldMappings = List(
            SqlField("id", Program.Id, key = true),
            SqlField("existence", Program.Existence, hidden = true),
            SqlField("name", Program.Name),
            SqlObject("users", Join(Program.Id, ProgramUser.ProgramId))
          ),
        ),
        ObjectMapping(
          tpe = ProgramUserType,
          fieldMappings = List(
            SqlField("programId", ProgramUser.ProgramId, hidden = true, key = true),
            SqlField("userId", ProgramUser.UserId, key = true),
            SqlField("role", ProgramUser.Role),
            SqlObject("user", Join(ProgramUser.UserId, User.Id))
          ),
        ),
        LeafMapping[lucuma.core.model.User.Id](UserIdType),
        LeafMapping[lucuma.core.model.Program.Id](ProgramIdType),
        LeafMapping[ProgramUserRole](ProgramUserRoleType),
      )

    // And the elaborator itself. A case for each query/mutation.
    val elaborator = Map[TypeRef, PartialFunction[Select, Result[Query]]](
      QueryType -> {

        case Select("program", List(
          ProgramIdBinding("programId", rPid),
          BooleanBinding("includeDeleted", rIncludeDeleted)
        ), child) =>
          (rPid, rIncludeDeleted).parMapN { (pid, includeDeleted) =>
            Select("program", Nil,
              Unique(
                Filter(
                  And(
                    Predicates.hasProgramId(pid),
                    Predicates.includeDeleted(includeDeleted),
                    Predicates.isVisibleTo(user),
                  ),
                  child
                )
              )
            )
          }

        case Select("programs", List(
          ProgramIdBinding.List.NullableOptional("programIds", rOptPids),
          BooleanBinding("includeDeleted", rIncludeDeleted)
        ), child) =>
          (rOptPids, rIncludeDeleted).parMapN { (optPids, includeDeleted) =>
            Select("programs", Nil,
              Filter(
                And(
                  Predicates.hasProgramId(optPids),
                  Predicates.includeDeleted(includeDeleted),
                  Predicates.isVisibleTo(user)
                ),
                child
              )
            )
          }

      },
      MutationType -> {

        case Select("createProgram", List(
          Binding("input", ObjectValue(List(
            NonEmptyStringBinding.NullableOptional("name", rName)
          )))
        ), child) =>
          rName.map { oName =>
            Environment(
              Env("name" -> oName),
              Select("createProgram", Nil, child)
            )
          }

        case Select("updateProgram", List(
          Binding("input", ObjectValue(List(
            ProgramIdBinding("programId", rProgramId),
            ExistenceBinding.Optional("existence", rExistence),
            NonEmptyStringBinding.OptionalNullable("name", rName),
          )))
        ), child) =>
          (rProgramId, rExistence, rName).mapN { (pid, ex, name) =>
            Environment(
              Env(
                "programId" -> pid,
                "existence" -> ex,
                "name"      -> name,
              ),
              Select("updateProgram", Nil, child)
            )
          }


      }
    )

    // Done.
    Snippet(schema, typeMappings, elaborator)

  }


}
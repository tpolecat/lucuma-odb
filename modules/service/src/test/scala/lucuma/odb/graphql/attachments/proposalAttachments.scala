// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql

package attachments

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import io.circe.Json
import io.circe.literal.*
import io.circe.syntax.*
import lucuma.core.model.Program
import lucuma.core.model.User
import lucuma.odb.FMain
import lucuma.odb.data.Tag
import lucuma.odb.util.Codecs.*
import natchez.Trace.Implicits.noop
import org.http4s.*
import skunk.*
import skunk.codec.all.*
import skunk.syntax.all.*

import java.util.UUID

class proposalAttachments extends AttachmentsSuite {

  def assertAttachmentsGql(
    user:        User,
    programId:   Program.Id,
    expectedTas: TestAttachment*
  ): IO[Unit] =
    expect(
      user = user,
      query = s"""
          query {
            program(programId: "$programId") {
              proposalAttachments {
                attachmentType
                fileName
                description
                checked
                fileSize
              }
            }
          }
        """,
      expected = Right(
        Json.obj(
          "program" -> expected(expectedTas: _*)
        )
      )
    )

  def updateAttachmentsGql(
    user:        User,
    programId:   Program.Id,
    WHERE:       String,
    SET:         String,
    expectedTas: TestAttachment*
  ): IO[Unit] =
    expect(
      user = user,
      query = s"""
        mutation {
          updateProposalAttachments(
            input: {
              programId: "$programId"
              WHERE: """ + WHERE + """
              SET: """ + SET + """
            }
          ) {
            proposalAttachments {
              attachmentType
              fileName
              description
              checked
              fileSize
            }
          }
        }
      """,
      expected = Right(
        Json.obj(
          "updateProposalAttachments" -> expected(expectedTas: _*)
        )
      )
    )

  def expected(attachments: TestAttachment*): Json =
    Json.obj(
      "proposalAttachments" -> Json.fromValues(
        attachments.map(ta =>
          Json.obj(
            "attachmentType" -> ta.attachmentType.toUpperCase.asJson,
            "fileName"       -> ta.fileName.asJson,
            "description"    -> ta.description.asJson,
            "checked"        -> ta.checked.asJson,
            "fileSize"       -> ta.content.length.asJson
          )
        )
      )
    )

  def insertAttachment(
    user:      User,
    programId: Program.Id,
    ta:        TestAttachment
  ): Resource[IO, Response[IO]] =
    server.flatMap { svr =>
      val uri =
        (svr.baseUri / "attachment" / "proposal" / programId.toString / ta.attachmentType)
          .withQueryParam("fileName", ta.fileName)
          .withOptionQueryParam("description", ta.description)

      val request = Request[IO](
        method = Method.POST,
        uri = uri,
        headers = Headers(authHeader(user))
      ).withEntity(ta.content)

      client.run(request)
    }

  def updateAttachment(
    user:         User,
    programId:    Program.Id,
    ta:           TestAttachment
  ): Resource[IO, Response[IO]] =
    server.flatMap { svr =>
      val uri =
        (svr.baseUri / "attachment" / "proposal" / programId.toString / ta.attachmentType)
          .withQueryParam("fileName", ta.fileName)
          .withOptionQueryParam("description", ta.description)

      val request = Request[IO](
        method = Method.PUT,
        uri = uri,
        headers = Headers(authHeader(user))
      ).withEntity(ta.content)

      client.run(request)
    }

  def getAttachment(
    user:      User,
    programId: Program.Id,
    ta:        TestAttachment
  ): Resource[IO, Response[IO]] =
    server.flatMap { svr =>
      var uri     = svr.baseUri / "attachment" / "proposal" / programId.toString / ta.attachmentType
      var request = Request[IO](
        method = Method.GET,
        uri = uri,
        headers = Headers(authHeader(user))
      )

      client.run(request)
    }

  def deleteAttachment(
    user:      User,
    programId: Program.Id,
    ta:        TestAttachment
  ): Resource[IO, Response[IO]] =
    server.flatMap { svr =>
      var uri     = svr.baseUri / "attachment" / "proposal" / programId.toString / ta.attachmentType
      var request = Request[IO](
        method = Method.DELETE,
        uri = uri,
        headers = Headers(authHeader(user))
      )

      client.run(request)
    }

  def getRemoteIdFromDb(pid: Program.Id, ta: TestAttachment): IO[UUID] = {
    val query = 
      sql"""
        select c_remote_id from t_proposal_attachment 
        where c_program_id = $program_id and c_attachment_type = $tag
      """.query(uuid)
    FMain.databasePoolResource[IO](databaseConfig).flatten
      .use(_.prepareR(query).use(_.unique(pid, Tag(ta.attachmentType)))
    )
  }

  val file1A           = TestAttachment("file1", "science", "A description".some, "Hopeful")
  val file1B           = TestAttachment("file1", "science", none, "New contents")
  val file1C           = TestAttachment("file1", "team", none, "Same name, different type")
  val file1Empty       = TestAttachment("file1", "science", "Thing".some, "")
  val file1InvalidType = TestAttachment("file1", "NotAType", none, "It'll never make it")
  val file2            = TestAttachment("file2", "team", "Masked".some, "Zorro")
  val fileWithPath     = TestAttachment("this/file.txt", "science", none, "Doesn't matter")
  val missingFileName  = TestAttachment("", "science", none, "Doesn't matter")
  // same attachment type as file1A, but different name, etc.
  val file3            = TestAttachment("different", "science", "Unmatching file name".some, "Something different")

  test("successful insert, download and delete") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(pi, pid, file1A).expectOk
      rid    <- getRemoteIdFromDb(pid, file1A)
      fileKey = awsConfig.proposalFileKey(pid, rid)
      _      <- assertS3(fileKey, file1A.content)
      _      <- assertAttachmentsGql(pi, pid, file1A)
      _      <- getAttachment(pi, pid, file1A).expectBody(file1A.content)
      _      <- deleteAttachment(pi, pid, file1A).expectOk
      _      <- assertS3NotThere(fileKey)
      _      <- assertAttachmentsGql(pi, pid)
      _      <- getAttachment(pi, pid, file1A).withExpectation(Status.NotFound)
    } yield ()
  }

  test("successful insert, download and delete of multiple files") {
    for {
      pid  <- createProgramAs(pi)
      _    <- insertAttachment(pi, pid, file1A).expectOk
      rid1 <- getRemoteIdFromDb(pid, file1A)
      fk1   = awsConfig.proposalFileKey(pid, rid1)
      _    <- assertS3(fk1, file1A.content)
      _    <- insertAttachment(pi, pid, file2).expectOk
      rid2 <- getRemoteIdFromDb(pid, file2)
      fk2   = awsConfig.proposalFileKey(pid, rid2)
      _    <- assertS3(fk2, file2.content)
      _    <- assertAttachmentsGql(pi, pid, file2, file1A)
      _    <- getAttachment(pi, pid, file1A).expectBody(file1A.content)
      _    <- getAttachment(pi, pid, file2).expectBody(file2.content)
      _    <- deleteAttachment(pi, pid, file1A).expectOk
      _    <- getAttachment(pi, pid, file1A).withExpectation(Status.NotFound)
      _    <- getAttachment(pi, pid, file2).expectBody(file2.content)
      _    <- assertS3NotThere(fk1)
      _    <- assertS3(fk2, file2.content)
      _    <- assertAttachmentsGql(pi, pid, file2)
    } yield ()
  }

  test("update with different name is successful") {
    for {
      pid    <- createProgramAs(pi)
      _       = assertEquals(file1A.attachmentType, file3.attachmentType)
      _      <- insertAttachment(pi, pid, file1A).expectOk
      rid    <- getRemoteIdFromDb(pid, file1A)
      fileKey = awsConfig.proposalFileKey(pid, rid)
      _      <- assertS3(fileKey, file1A.content)
      _      <- assertAttachmentsGql(pi, pid, file1A)
      _      <- getAttachment(pi, pid, file1A).expectBody(file1A.content)
      _      <- updateAttachment(pi, pid, file3).expectOk
      rid2   <- getRemoteIdFromDb(pid, file1A) // use the original type
      _       = assertNotEquals(rid, rid2)
      fk2     = awsConfig.proposalFileKey(pid, rid2)
      _      <- assertS3(fk2, file3.content)
      _      <- assertS3NotThere(fileKey)
      _      <- assertAttachmentsGql(pi, pid, file3)
      _      <- getAttachment(pi, pid, file1A).expectBody(file3.content)
    } yield ()
  }

  test("update with same name is successful") {
    for {
      pid    <- createProgramAs(pi)
      _       = assertEquals(file1A.attachmentType, file1B.attachmentType)
      _      <- insertAttachment(pi, pid, file1A).expectOk
      rid    <- getRemoteIdFromDb(pid, file1A)
      fileKey = awsConfig.proposalFileKey(pid, rid)
      _      <- assertS3(fileKey, file1A.content)
      _      <- assertAttachmentsGql(pi, pid, file1A)
      _      <- getAttachment(pi, pid, file1A).expectBody(file1A.content)
      _      <- updateAttachment(pi, pid, file1B).expectOk
      rid2   <- getRemoteIdFromDb(pid, file1A)
      _       = assertNotEquals(rid, rid2)
      fk2     = awsConfig.proposalFileKey(pid, rid2)
      _      <- assertS3(fk2, file1B.content)
      _      <- assertS3NotThere(fileKey)
      _      <- assertAttachmentsGql(pi, pid, file1B)
      _      <- getAttachment(pi, pid, file1A).expectBody(file1B.content)
    } yield ()
  }

  test("update of non-existent attachment is NotFound") {
    for {
      pid <- createProgramAs(pi)
      _   <- updateAttachment(pi, pid, file1A).withExpectation(Status.NotFound)
    } yield ()
  }

  test("insert with duplicate type is a BadRequest") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(pi, pid, file1A).expectOk
      rid    <- getRemoteIdFromDb(pid, file1A)
      fileKey = awsConfig.proposalFileKey(pid, rid)
      _      <- assertS3(fileKey, file1A.content)
      _      <- assertAttachmentsGql(pi, pid, file1A)
      _      <- insertAttachment(pi, pid, file3).withExpectation(Status.BadRequest, "Duplicate attachment type")
    } yield ()
  }
  test("insert with duplicate name is a BadRequest") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(pi, pid, file1A).expectOk
      rid    <- getRemoteIdFromDb(pid, file1A)
      fileKey = awsConfig.proposalFileKey(pid, rid)
      _      <- assertS3(fileKey, file1A.content)
      _      <- assertAttachmentsGql(pi, pid, file1A)
      _      <- insertAttachment(pi, pid, file1C).withExpectation(Status.BadRequest, "Duplicate file name")
    } yield ()
  }

  test("update with duplicate name is a BadRequest") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(pi, pid, file1A).expectOk
      rid    <- getRemoteIdFromDb(pid, file1A)
      fileKey = awsConfig.proposalFileKey(pid, rid)
      _      <- insertAttachment(pi, pid, file2).expectOk
      rid2   <- getRemoteIdFromDb(pid, file2)
      fk2     = awsConfig.proposalFileKey(pid, rid2)
      _      <- assertAttachmentsGql(pi, pid, file2, file1A)
      _      <- updateAttachment(pi, pid, file1C).withExpectation(Status.BadRequest, "Duplicate file name")
      _      <- assertAttachmentsGql(pi, pid, file2, file1A)
      _      <- getAttachment(pi, pid, file2).expectBody(file2.content)
    } yield ()
  }

  test("empty file update fails, doesn't overwrite previous") {
    for {
      pid    <- createProgramAs(pi)
      _       = assertEquals(file1A.attachmentType, file1Empty.attachmentType)
      _      <- insertAttachment(pi, pid, file1A).expectOk
      rid    <- getRemoteIdFromDb(pid, file1A)
      fileKey = awsConfig.proposalFileKey(pid, rid)
      _      <- assertS3(fileKey, file1A.content)
      _      <- assertAttachmentsGql(pi, pid, file1A)
      _      <- updateAttachment(pi, pid, file1Empty).withExpectation(Status.InternalServerError)
      _      <- assertS3(fileKey, file1A.content)
      _      <- assertAttachmentsGql(pi, pid, file1A)
    } yield ()
  }

  test("invalid attachment type insert is BadRequest") {
    for {
      pid <- createProgramAs(pi)
      _   <- insertAttachment(pi, pid, file1InvalidType).withExpectation(Status.BadRequest, "Invalid attachment type")
    } yield ()
  }

  test("insert with empty file name is BadRequest") {
    for {
      pid <- createProgramAs(pi)
      _   <- insertAttachment(pi, pid, missingFileName).withExpectation(Status.BadRequest, "File name is required")
    } yield ()
  }

  test("update with empty file name is BadRequest") {
    for {
      pid <- createProgramAs(pi)
      _    = assertEquals(file1A.attachmentType, missingFileName.attachmentType)
      _   <- insertAttachment(pi, pid, file1A).expectOk
      _   <- updateAttachment(pi, pid, missingFileName).withExpectation(Status.BadRequest, "File name is required")
    } yield ()
  }

  test("file name with path insert fails") {
    for {
      pid <- createProgramAs(pi)
      _   <- insertAttachment(pi, pid, fileWithPath).withExpectation(Status.BadRequest, "File name cannot include a path")
    } yield ()
  }

  test("file name with path update fails") {
    for {
      pid <- createProgramAs(pi)
      _    = assertEquals(file1A.attachmentType, fileWithPath.attachmentType)
      _   <- insertAttachment(pi, pid, file1A).expectOk
      _   <- updateAttachment(pi, pid, fileWithPath).withExpectation(Status.BadRequest, "File name cannot include a path")
    } yield ()
  }

  test("pi can only insert to their own programs") {
    for {
      pid1 <- createProgramAs(pi)
      pid2 <- createProgramAs(pi2)
      _    <- insertAttachment(pi, pid2, file1A).withExpectation(Status.Forbidden)
      _    <- insertAttachment(pi2, pid1, file1A).withExpectation(Status.Forbidden)
      _    <- assertAttachmentsGql(pi, pid1)
      _    <- assertAttachmentsGql(pi2, pid2)
    } yield ()
  }

  test("pi can only update their own programs") {
    for {
      pid <- createProgramAs(pi)
      _   <- insertAttachment(pi, pid, file1A).expectOk
      _   <- updateAttachment(pi2, pid, file3).withExpectation(Status.Forbidden)
    } yield ()
  }

  test("pi can only see their own programs") {
    for {
      pid1 <- createProgramAs(pi)
      pid2 <- createProgramAs(pi2)
      _    <- insertAttachment(pi, pid1, file1A).expectOk
      _    <- insertAttachment(pi2, pid2, file1B).expectOk
      _    <- assertAttachmentsGql(pi, pid1, file1A)
      _    <- assertAttachmentsGql(pi2, pid2, file1B)
    } yield ()
  }

  test("pi can only download from their own programs") {
    for {
      pid1 <- createProgramAs(pi)
      pid2 <- createProgramAs(pi2)
      _    <- insertAttachment(pi, pid1, file1A).expectOk
      rid1 <- getRemoteIdFromDb(pid1, file1A)
      fk1   = awsConfig.proposalFileKey(pid1, rid1)
      _    <- assertS3(fk1, file1A.content)
      _    <- assertAttachmentsGql(pi, pid1, file1A)
      _    <- insertAttachment(pi2, pid2, file2).expectOk
      rid2 <- getRemoteIdFromDb(pid2, file2)
      fk2   = awsConfig.proposalFileKey(pid2, rid2)
      _    <- assertS3(fk2, file2.content)
      _    <- assertAttachmentsGql(pi2, pid2, file2)
      _    <- getAttachment(pi, pid2, file2).withExpectation(Status.Forbidden)
      _    <- getAttachment(pi2, pid1, file1A).withExpectation(Status.Forbidden)
    } yield ()
  }

  test("pi can only delete from their own programs") {
    for {
      pid1 <- createProgramAs(pi)
      pid2 <- createProgramAs(pi2)
      _    <- insertAttachment(pi, pid1, file1A).expectOk
      rid1 <- getRemoteIdFromDb(pid1, file1A)
      fk1   = awsConfig.proposalFileKey(pid1, rid1)
      _    <- assertS3(fk1, file1A.content)
      _    <- assertAttachmentsGql(pi, pid1, file1A)
      _    <- insertAttachment(pi2, pid2, file2).expectOk
      rid2 <- getRemoteIdFromDb(pid2, file2)
      fk2   = awsConfig.proposalFileKey(pid2, rid2)
      _    <- assertS3(fk2, file2.content)
      _    <- assertAttachmentsGql(pi2, pid2, file2)
      _    <- deleteAttachment(pi, pid2, file2).withExpectation(Status.Forbidden)
      _    <- deleteAttachment(pi2, pid1, file1A).withExpectation(Status.Forbidden)
    } yield ()
  }

  test("service user can manage any program's attachments") {
    for {
      pid    <- createProgramAs(pi)
      _       = assertEquals(file1A.attachmentType, file3.attachmentType)
      _      <- insertAttachment(service, pid, file1A).expectOk
      rid    <- getRemoteIdFromDb(pid, file1A)
      fileKey = awsConfig.proposalFileKey(pid, rid)
      _      <- assertS3(fileKey, file1A.content)
      _      <- assertAttachmentsGql(service, pid, file1A)
      _      <- getAttachment(service, pid, file1A).expectBody(file1A.content)
      _      <- updateAttachment(service, pid, file3).expectOk
      rid2   <- getRemoteIdFromDb(pid, file3)
      _       = assertNotEquals(rid, rid2)
      fk2     = awsConfig.proposalFileKey(pid, rid2)
      _      <- assertS3(fk2, file3.content)
      _      <- assertS3NotThere(fileKey)
      _      <- deleteAttachment(service, pid, file3).expectOk
      _      <- assertS3NotThere(fk2)
      _      <- assertAttachmentsGql(service, pid)
    } yield ()
  }

  test("update single attachment metadata: description") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1A).expectOk
      newDesc = "New description"
      newTa   = file1A.copy(description = newDesc.some)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ attachmentType: { EQ: ${file1A.upperType} }}""",
                                     SET = s"""{ description: "$newDesc" }""",
                                     newTa
                )
    } yield ()
  }

  test("update single attachment metadata: unset description") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1A).expectOk
      newDesc = "New description"
      newTa   = file1A.copy(description = none)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ attachmentType: { EQ: ${file1A.upperType} }}""",
                                     SET = s"""{ description: null }""",
                                     newTa
                )
    } yield ()
  }

  test("update single attachment metadata: checked") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1A).expectOk
      newTa   = file1A.copy(checked = true)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ attachmentType: { EQ: ${file1A.upperType} }}""",
                                     SET = s"""{ checked: true }""",
                                     newTa
                )
    } yield ()
  }

  test("bulk update attachments metadata: by name") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1A).expectOk
      _      <- insertAttachment(service, pid, file2).expectOk
      newDesc = "updated"
      newTa1  = file1A.copy(description = newDesc.some, checked = true)
      newTa2  = file2.copy(description = newDesc.some, checked = true)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ fileName: { LIKE: "file%"}}""",
                                     SET = s"""{ checked: true, description: "$newDesc" }""",
                                     newTa1,
                                     newTa2
                )
    } yield ()
  }

  test("update attachments metadata: by checked") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1A).expectOk
      _      <- insertAttachment(service, pid, file2).expectOk
      ta2c    = file2.copy(checked = true)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ attachmentType: { EQ: ${file2.upperType} }}""",
                                     SET = s"""{ checked: true }""",
                                     ta2c
                )
      newDesc = "Verified"
      newTa2  = ta2c.copy(description = newDesc.some)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ checked: true }""",
                                     SET = s"""{ description: "$newDesc" }""",
                                     newTa2
                )
    } yield ()
  }

  test("update attachments metadata: by description") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1A).expectOk
      _      <- insertAttachment(service, pid, file2).expectOk
      newDesc = "updated"
      newTa2  = file2.copy(description = newDesc.some, checked = true)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ description: { NLIKE: "%script%" }}""",
                                     SET = s"""{ checked: true, description: "$newDesc" }""",
                                     newTa2
                )
    } yield ()
  }

  test("update attachments metadata: by null description") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1B).expectOk
      _      <- insertAttachment(service, pid, file2).expectOk
      newDesc = "No longer null!"
      newTa1  = file1B.copy(description = newDesc.some)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ description: { IS_NULL: true }}""",
                                     SET = s"""{ description: "$newDesc" }""",
                                     newTa1
                )
    } yield ()
  }

  test("update attachments metadata: by attachment type") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1B).expectOk
      _      <- insertAttachment(service, pid, file2).expectOk
      newDesc = "Found"
      newTa1  = file1B.copy(description = newDesc.some)
      newTa2  = file2.copy(description = newDesc.some)
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ attachmentType: { IN: [SCIENCE, TEAM] }}""",
                                     SET = s"""{ description: "$newDesc" }""",
                                     newTa1,
                                     newTa2
                )
    } yield ()
  }

  test("update attachments metadata: no matches") {
    for {
      pid    <- createProgramAs(pi)
      _      <- insertAttachment(service, pid, file1B).expectOk
      _      <- updateAttachmentsGql(pi,
                                     pid,
                                     WHERE = s"""{ description: { IS_NULL: false }}""",
                                     SET = s"""{ description: "FOUND" }""",
                )
    } yield ()
  }
}

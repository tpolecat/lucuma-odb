// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package feature

import cats.effect.IO
import cats.syntax.all._
import io.circe.Json
import io.circe.literal._
import lucuma.core.model.Program
import lucuma.core.model.Semester
import lucuma.core.model.User
import lucuma.odb.data.ProgramReference
import lucuma.odb.data.ProposalReference


class reference extends OdbSuite {

  val pi       = TestUsers.Standard.pi(1, 101)
  val guest    = TestUsers.guest(2)
  val service  = TestUsers.service(3)
  val staff    = TestUsers.Standard.staff(4, 104)

  val validUsers = List(pi, guest, service, staff)

  val sem2024A   = Semester.unsafeFromString("2024A")
  val ref2024A1  = ProposalReference.fromString.unsafeGet("G-2024A-0001")
  val ref2024A1Q = ProgramReference.fromString.unsafeGet("G-2024A-0001-Q")
  val ref2024A1C = ProgramReference.fromString.unsafeGet("G-2024A-0001-C")

  val sem2024B   = Semester.unsafeFromString("2024B")
  val ref2024B1  = ProposalReference.fromString.unsafeGet("G-2024B-0001")
  val ref2024B2  = ProposalReference.fromString.unsafeGet("G-2024B-0002")
  val ref2024B3  = ProposalReference.fromString.unsafeGet("G-2024B-0003")

  val sem2025A   = Semester.unsafeFromString("2025A")
  val ref2025A1  = ProposalReference.fromString.unsafeGet("G-2025A-0001")

  val sem2025B   = Semester.unsafeFromString("2025B")
  val ref2025B1  = ProposalReference.fromString.unsafeGet("G-2025B-0001")
  val ref2025B2  = ProposalReference.fromString.unsafeGet("G-2025B-0002")

  def createProgramWithSemester(semester: String): IO[Program.Id] =
    query(pi,
      s"""
        mutation {
          createProgram(
            input: {
               SET: {
                  semester: "$semester"
               }
            }
          ) {
            program { id }
          }
        }
      """
    ).flatMap { js =>
      js.hcursor
        .downFields("createProgram", "program", "id")
        .as[Program.Id]
        .leftMap(f => new RuntimeException(f.message))
        .liftTo[IO]
    }

  def pidsWhere(where: String): IO[List[Program.Id]] =
    query(pi, s"query { programs(WHERE: $where) { matches { id } } }")
      .flatMap {
        _.hcursor
         .downFields("programs", "matches")
         .values
         .toList
         .flatMap(_.toList)
         .traverse { _.hcursor.downField("id").as[Program.Id] }
         .leftMap(f => new RuntimeException(f.message))
         .liftTo[IO]
      }

  def proposalRefsWhere(where: String): IO[List[ProposalReference]] =
    query(pi, s"query { programs(WHERE: $where) { matches { proposal { reference { label } } } } }")
      .flatMap {
        _.hcursor
         .downFields("programs", "matches")
         .values
         .toList
         .flatMap(_.toList)
         .flatTraverse {
           _.hcursor
            .downFields("proposal", "reference", "label")
            .success
            .toList
            .traverse(_.as[ProposalReference])
         }
         .leftMap(f => new RuntimeException(f.message))
         .liftTo[IO]
      }

  def programRefsWhere(where: String): IO[List[ProgramReference]] =
    query(pi, s"query { programs(WHERE: $where) { matches { reference { label } } } }")
      .flatMap {
        _.hcursor
         .downFields("programs", "matches")
         .values
         .toList
         .flatMap(_.toList)
         .flatTraverse {
           _.hcursor
            .downFields("reference", "label")
            .success
            .toList
            .traverse(_.as[ProgramReference])
         }
         .leftMap(f => new RuntimeException(f.message))
         .liftTo[IO]
      }

  test("submit proposals") {
    for {
      pid0 <- createProgramAs(pi)
      _    <- addProposal(pi, pid0)
      ref0 <- submitProposal(pi, pid0, sem2024B.some)

      pid1 <- createProgramAs(pi)
      _    <- addProposal(pi, pid1)
      ref1 <- submitProposal(pi, pid1, sem2024B.some)

      pid2 <- createProgramAs(pi)
      _    <- addProposal(pi, pid2)
      ref2 <- submitProposal(pi, pid2, sem2025A.some)
    } yield {
      assertEquals(ref0, ref2024B1)
      assertEquals(ref1, ref2024B2)
      assertEquals(ref2, ref2025A1)
    }
  }

  test("lookup via proposal ref") {
    expect(
      user  = pi,
      query = s"""
        query {
          program(proposalReference: "G-2024B-0001") {
            reference { label }
            proposal {
              reference {
                label
                semester
                semesterIndex
              }
            }
          }
        }
      """,
      expected = Right(
        json"""
          {
            "program": {
              "reference": null,
              "proposal": {
                "reference": {
                  "label": ${ref2024B1.label},
                  "semester": "2024B",
                  "semesterIndex": 1
                }
              }
            }
          }
        """
      )
    )
  }

  test("lookup, no pid, no ref") {
    expect(
      user  = pi,
      query = s"""
        query {
          program {
            proposal { reference { label } }
          }
        }
      """,
      expected = Right(
        json"""
          {
            "program": null
          }
        """
      )
    )
  }

  test("select via WHERE proposal IS_NULL = true") {
    for {
      pid0 <- createProgramWithSemester("2020A")
      pid1 <- createProgramWithSemester("2020A")
      _    <- addProposal(pi, pid1)
      res0 <- pidsWhere("{ proposal: { IS_NULL: true } }")
      res1 <- pidsWhere("{ proposal: { reference: { IS_NULL: true } } }")
    } yield {
      assertEquals(res0, List(pid0))
      assertEquals(res1, List(pid0, pid1))
    }
  }

  test("select via WHERE proposal IS_NULL = false") {
    assertIO(
      proposalRefsWhere( s"""
        {
          proposal: {
            IS_NULL: false
            reference: { IS_NULL: false }
          }
        }
      """),
      List(ref2024B1, ref2024B2, ref2025A1)
    )
  }

  test("select via WHERE proposal reference label LIKE") {
    assertIO(
      proposalRefsWhere( s"""{ proposal: { reference: { label: { LIKE: "G-2024B-%" } } } }"""),
      List(ref2024B1, ref2024B2)
    )
  }

  test("select via WHERE proposal reference semester") {
    assertIO(
      proposalRefsWhere( s"""{ proposal: { reference: { semester: { EQ: "2024B" } } } }"""),
      List(ref2024B1, ref2024B2)
    )
  }

  test("select via WHERE proposal reference semesterIndex") {
    assertIO(
      proposalRefsWhere( s"""{ proposal: { reference: { semesterIndex: { GT: 1 } } } }"""),
      List(ref2024B2)
    )
  }

  val ref2010A1 = ProposalReference.fromString.unsafeGet("G-2010A-0001")

  // TODO: proposal status mutations
  // I think we want to remove semester as an independently settable property.
  // It should instead be required on proposal submission.
  test("TEMP: set semester on create, then submit") {
    val res = for {
      pid <- createProgramWithSemester("2010A")
      _   <- addProposal(pi, pid)
      ref <- submitProposal(pi, pid, none) // no semester
    } yield ref

    assertIO(res, ref2010A1)
  }

  // TODO: proposal status mutations
  // I think we want to remove semester as an independently settable property.
  test("TEMP: cannot unset semester after submit") {
    fetchPid(pi, ref2010A1).flatMap { pid =>
      expect(
        user = pi,
        query = s"""
            mutation {
              updatePrograms(
                input: {
                  SET: {
                    semester: null
                  }
                  WHERE: {
                    proposal: {
                      reference: {
                        semester: {
                          EQ: "2010A"
                        }
                      }
                    }
                  }
                }
              ) {
                programs {
                  proposal {
                    reference {
                      label
                    }
                  }
                }
              }
            }
        """,
        expected = Left(List(
          s"Submitted program $pid must be associated with a semester."
        ))
      )
    }
  }

  // TODO: proposal status mutations
  // I think this interaction used in this test will be removed.  When submitting
  // you'll need to supply the semester.
  test("TEMP: unsubmit, resubmit, same reference") {
    expect(
      user = pi,
      query = s"""
        mutation {
          updatePrograms(
            input: {
              SET: {
                proposalStatus: NOT_SUBMITTED
              }
              WHERE: {
                proposal: {
                  reference: {
                    label: {
                      EQ: "${ref2010A1.label}"
                    }
                  }
                }
              }
            }
          ) {
            programs {
              proposalStatus
            }
          }
        }
        """,
      expected = Right(
        json"""
          {
            "updatePrograms" : {
              "programs": [
                {
                  "proposalStatus": "NOT_SUBMITTED"
                }
              ]
            }
          }
        """
      )
    ) >>
    expect(
      user = pi,
      query = s"""
        mutation {
          updatePrograms(
            input: {
              SET: {
                proposalStatus: SUBMITTED
              }
              WHERE: {
                proposal: {
                  reference: {
                    label: {
                      EQ: "${ref2010A1.label}"
                    }
                  }
                }
              }
            }
          ) {
            programs {
              proposal { reference { label } }
            }
          }
        }
      """,
      expected = Right(
        json"""
          {
            "updatePrograms" : {
              "programs": [
                {
                  "proposal": {
                    "reference": {
                      "label": ${ref2010A1.label}
                    }
                  }
                }
              ]
            }
          }
        """
      )
    )
  }

  // TODO: proposal status mutations
  // You won't be able to change the semester without first unsubmitting.
  test("TEMP: change semester, changes reference") {
    // G-2010A-0001 -> assign semester 2024B
    // G-2024B-0001 and 00002 already taken, so we get G-2024B-0003
    expect(
      user = pi,
      query = s"""
        mutation {
          updatePrograms(
            input: {
              SET: {
                semester: "2024B"
              }
              WHERE: {
                proposal: {
                  reference: {
                    label: {
                      EQ: "${ref2010A1.label}"
                    }
                  }
                }
              }
            }
          ) {
            programs {
              proposal { reference { label } }
            }
          }
        }
        """,
      expected = Right(
        json"""
          {
            "updatePrograms" : {
              "programs": [
                {
                  "proposal": {
                    "reference": {
                      "label": ${ref2024B3.label}
                    }
                  }
                }
              ]
            }
          }
        """
      )
    )
  }

  test("accept proposal") {
    for {
      pid  <- createProgramAs(pi)
      _    <- addProposal(pi, pid)
      _    <- submitProposal(pi, pid, sem2024A.some)
      ref0 <- fetchProgramReference(pi, pid)
      ref1 <- acceptProposal(staff, pid)
    } yield {
      assert(ref0.isEmpty)
      assertEquals(ref1, ref2024A1Q.some)
    }
  }

  test("program reference SCI fields") {
    for {
      pid <- createProgramAs(pi)
      _   <- addProposal(pi, pid)
      _   <- submitProposal(pi, pid, sem2024A.some)
      _   <- acceptProposal(staff, pid)
      _   <- expect(pi, s"""
          query {
            program(programId: "$pid") {
              reference {
                label
                ... on ScienceProgramReference {
                  scienceSubtype
                  semester
                  semesterIndex
                }
              }
            }
          }
        """,
        json"""
          {
            "program": {
              "reference": {
                "label": "G-2024A-0002-Q",
                "scienceSubtype": "QUEUE",
                "semester": "2024A",
                "semesterIndex": 2
              }
            }
          }
        """.asRight
      )
    } yield ()
  }

  test("change propsal class in accepted proposal") {
    def toClassical(pid: Program.Id): IO[Json] =
      query(
        pi,
        s"""
          mutation {
            updatePrograms(
              input: {
                SET: {
                  proposal: {
                    proposalClass: {
                      classical: { minPercentTime: 50 }
                    }
                  }
                }
                WHERE: { id: { EQ: "$pid" } }
              }
            ) { programs { id } }
          }
        """
      )

    for {
      pid  <- fetchPid(pi, ref2024A1)
      _    <- toClassical(pid)
      prog <- fetchProgramReference(pi, pid)
    } yield assertEquals(prog, ref2024A1C.some)
  }

  def setProgramReference(pid: Program.Id, set: String): IO[Option[String]] =
    query(
      pi,
      s"""
        mutation {
          setProgramReference(input: {
            programId: "$pid"
            SET: { $set }
          }) {
            reference { label }
          }
        }
      """
    ).flatMap {
      _.hcursor
       .downFields("setProgramReference", "reference", "label")
       .success
       .traverse(_.as[ProgramReference])
       .leftMap(f => new RuntimeException(f.message))
       .liftTo[IO]
       .map(_.map(_.label))
    }

  test("setProposalReference illegal attempt to define multiple references") {
    createProgramAs(pi).flatMap { pid =>
      expect(pi,
        s"""
          mutation {
            setProgramReference(input: {
              programId: "$pid"
              SET: {
                calibration: { semester: "2025B", instrument: GMOS_SOUTH },
                engineering: { semester: "2025B", instrument: GMOS_SOUTH }
              }
            }) {
              reference { label }
            }
          }
        """,
        List("Argument 'input.SET' is invalid: Exactly one of 'calibration', 'engineering', 'example', 'library', or 'science' expected.").asLeft
      )
    }
  }

  test("setProposalReference, illegal semester") {
    createProgramAs(pi).flatMap { pid =>
      expect(pi,
        s"""
          mutation {
            setProgramReference(input: {
              programId: "$pid"
              SET: {
                calibration: { semester: "9999B", instrument: GMOS_SOUTH }
              }
            }) {
              reference { label }
            }
          }
        """,
        List("The maximum semester is capped at the current year +1 (9999B specified).").asLeft
      )
    }
  }

  test("setProposalReference CAL") {
    for {
      pid <- createProgramAs(pi)
      ref <- setProgramReference(pid, """calibration: { semester: "2025B", instrument: GMOS_SOUTH }""")
    } yield assertEquals(ref, "G-2025B-CAL-GMOSS-01".some)
  }

  test("setProposalReference CAL, different instrument has its own index") {
    for {
      pid <- createProgramAs(pi)
      ref <- setProgramReference(pid, """calibration: { semester: "2025B", instrument: GMOS_NORTH }""")
    } yield assertEquals(ref, "G-2025B-CAL-GMOSN-01".some)
  }

  test("program reference CAL fields") {
    expect(pi, s"""
      query {
        program(programReference: "G-2025B-CAL-GMOSS-01") {
          reference {
            label
            ... on CalibrationProgramReference {
              semester
              instrument
              semesterIndex
            }
          }
        }
      }""",
      json"""
        {
          "program": {
            "reference": {
              "label": "G-2025B-CAL-GMOSS-01",
              "semester": "2025B",
              "instrument": "GMOS_SOUTH",
              "semesterIndex": 1
            }
          }
        }
      """.asRight
    )
  }

  test("setProposalReference ENG") {
    for {
      pid <- createProgramAs(pi)
      ref <- setProgramReference(pid, """engineering: { semester: "2025B", instrument: GMOS_SOUTH }""")
    } yield assertEquals(ref, "G-2025B-ENG-GMOSS-01".some)
  }

  test("setProposalReference ENG, second time increases index") {
    for {
      pid <- createProgramAs(pi)
      ref <- setProgramReference(pid, """engineering: { semester: "2025B", instrument: GMOS_SOUTH }""")
    } yield assertEquals(ref, "G-2025B-ENG-GMOSS-02".some)
  }

  test("program reference ENG fields") {
    expect(pi, s"""
      query {
        program(programReference: "G-2025B-ENG-GMOSS-02") {
          reference {
            label
            ... on EngineeringProgramReference {
              semester
              instrument
              semesterIndex
            }
          }
        }
      }""",
      json"""
        {
          "program": {
            "reference": {
              "label": "G-2025B-ENG-GMOSS-02",
              "semester": "2025B",
              "instrument": "GMOS_SOUTH",
              "semesterIndex": 2
            }
          }
        }
      """.asRight
    )
  }

  test("setProposalReference XPL") {
    for {
      pid <- createProgramAs(pi)
      ref <- setProgramReference(pid, """example: { instrument: GMOS_SOUTH }""")
    } yield assertEquals(ref, "G-XPL-GMOSS".some)
  }

  test("setProposalReference XPL, already taken") {
    createProgramAs(pi).flatMap { pid =>
      expect(
        pi,
        s"""
          mutation {
            setProgramReference(input: {
              programId: "$pid"
              SET: { example: { instrument: GMOS_SOUTH } }
            }) {
              reference { label }
            }
          }
        """,
        List("Program reference 'G-XPL-GMOSS' already exists.").asLeft
      )
    }
  }

  test("program reference XPL fields") {
    expect(pi, s"""
      query {
        program(programReference: "G-XPL-GMOSS") {
          reference {
            label
            ... on ExampleProgramReference {
              instrument
            }
          }
        }
      }""",
      json"""
        {
          "program": {
            "reference": {
              "label": "G-XPL-GMOSS",
              "instrument": "GMOS_SOUTH"
            }
          }
        }
      """.asRight
    )
  }

  test("setProposalReference LIB") {
    for {
      pid <- createProgramAs(pi)
      ref <- setProgramReference(pid, """library: { instrument: GMOS_SOUTH, description: "FOO" }""")
    } yield assertEquals(ref, "G-LIB-GMOSS-FOO".some)
  }

  test("setProposalReference LIB, invalid description") {
    createProgramAs(pi).flatMap { pid =>
      expect(pi,
        s"""
          mutation {
            setProgramReference(input: {
              programId: "$pid"
              SET: {
                library: { instrument: GMOS_SOUTH, description: "x" }
              }
            }) {
              reference { label }
            }
          }
        """,
        List("Argument 'input.SET.library.description' is invalid: Predicate failed: \"x\".matches(\"^[A-Z0-9]+\").").asLeft
      )
    }
  }

  test("program reference LIB fields") {
    expect(pi, s"""
      query {
        program(programReference: "G-LIB-GMOSS-FOO") {
          reference {
            label
            ... on LibraryProgramReference {
              instrument
              description
            }
          }
        }
      }""",
      json"""
        {
          "program": {
            "reference": {
              "label": "G-LIB-GMOSS-FOO",
              "instrument": "GMOS_SOUTH",
              "description": "FOO"
            }
          }
        }
      """.asRight
    )
  }

  test("setProposalReference SCI, no proposal") {
    for {
      pid <- createProgramAs(pi)
      ref <- setProgramReference(pid, """science: { semester: "2025B", scienceSubtype: QUEUE }""")
    } yield assertEquals(ref, none)
  }

  test("setProposalReference SCI, yes proposal") {
    for {
      pid <- createProgramWithSemester("2025B")
      _   <- addProposal(pi, pid)
      _   <- acceptProposal(staff, pid)
      ref <- setProgramReference(pid, """science: { semester: "2025B", scienceSubtype: QUEUE }""")
    } yield assertEquals(ref, "G-2025B-0001-Q".some)
  }

  test("setProposalReference SCI, change subtype but index remains the same") {
    for {
      pid <- fetchPid(pi, ProgramReference.fromString.unsafeGet("G-2025B-0001-Q"))
      ref <- setProgramReference(pid, """science: { semester: "2025B", scienceSubtype: CLASSICAL }""")
    } yield assertEquals(ref, "G-2025B-0001-C".some)
  }

  test("setProposalReference SCI -> CAL -> SCI, index increases") {
    for {
      pid <- fetchPid(pi, ProgramReference.fromString.unsafeGet("G-2025B-0001-C"))
      _   <- setProgramReference(pid, """calibration: { semester: "2025B", instrument: GMOS_SOUTH }""")
      ref <- setProgramReference(pid, """science: { semester: "2025B", scienceSubtype: QUEUE }""")
    } yield assertEquals(ref, "G-2025B-0002-Q".some)
  }

  // Program references created above
  val ref25BCalGmosSouth01 = ProgramReference.fromString.unsafeGet("G-2025B-CAL-GMOSS-01")
  val ref25BCalGmosNorth01 = ProgramReference.fromString.unsafeGet("G-2025B-CAL-GMOSN-01")
  val ref25BEngGmosSouth01 = ProgramReference.fromString.unsafeGet("G-2025B-ENG-GMOSS-01")
  val ref25BEngGmosSouth02 = ProgramReference.fromString.unsafeGet("G-2025B-ENG-GMOSS-02")
  val refXplGmosSouth      = ProgramReference.fromString.unsafeGet("G-XPL-GMOSS")
  val refLibGmosSouthFoo   = ProgramReference.fromString.unsafeGet("G-LIB-GMOSS-FOO")
  val ref24ASci01C         = ProgramReference.fromString.unsafeGet("G-2024A-0001-C")
  val ref24ASci02Q         = ProgramReference.fromString.unsafeGet("G-2024A-0002-Q")
  val ref25BSci02Q         = ProgramReference.fromString.unsafeGet("G-2025B-0002-Q")

  test("select via WHERE program reference label LIKE") {
    assertIO(
      programRefsWhere( s"""{ reference: { label: { LIKE: "G-2025B-%" } } }"""),
      List(ref25BCalGmosSouth01, ref25BCalGmosNorth01, ref25BEngGmosSouth01, ref25BEngGmosSouth02, ref25BSci02Q)
    )
  }

  test("select via WHERE program reference semester") {
    assertIO(
      programRefsWhere( s"""{ reference: { semester: { EQ: "2025B" } } }"""),
      List(ref25BCalGmosSouth01, ref25BCalGmosNorth01, ref25BEngGmosSouth01, ref25BEngGmosSouth02, ref25BSci02Q)
    )
  }

  test("select via WHERE program reference semester, only ENG") {
    assertIO(
      programRefsWhere( s"""{ type: { EQ: ENGINEERING }, reference: { semester: { EQ: "2025B" } } }"""),
      List(ref25BEngGmosSouth01, ref25BEngGmosSouth02)
    )
  }

  test("select via WHERE program reference semesterIndex") {
    assertIO(
      programRefsWhere( s"""{ reference: { semesterIndex: { GT: 1 } } }"""),
      List(ref24ASci02Q, ref25BEngGmosSouth02, ref25BSci02Q)
    )
  }

  test("select via WHERE program reference instrument") {
    assertIO(
      programRefsWhere( s"""{ reference: { instrument: { EQ: GMOS_SOUTH } } }"""),
      List(ref25BCalGmosSouth01, ref25BEngGmosSouth01, ref25BEngGmosSouth02, refXplGmosSouth, refLibGmosSouthFoo)
    )
  }

  test("select via WHERE program reference description") {
    assertIO(
      programRefsWhere( s"""{ reference: { description: { EQ: "FOO" } } }"""),
      List(refLibGmosSouthFoo)
    )
  }

  test("select via WHERE program reference description not matching") {
    assertIO(
      programRefsWhere( s"""{ reference: { description: { EQ: "BAR" } } }"""),
      Nil
    )
  }

  test("select via WHERE program reference science subtype") {
    assertIO(
      programRefsWhere( s"""{ reference: { scienceSubtype: { EQ: CLASSICAL } } }"""),
      List(ref24ASci01C)
    )
  }
}

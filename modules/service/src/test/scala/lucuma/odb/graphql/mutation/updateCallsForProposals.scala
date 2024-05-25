// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.graphql
package mutation

import cats.effect.IO
import cats.syntax.either.*
import io.circe.Json
import io.circe.literal.*
import lucuma.core.model.CallForProposals
import lucuma.core.model.User

class updateCallsForProposals extends OdbSuite {

  val service    = TestUsers.service(3)
  val staff      = TestUsers.Standard.staff(4, 44)
  val validUsers = List(service, staff)

  val createCall: IO[CallForProposals.Id] =
    query(
      staff,
      s"""
        mutation {
          createCallForProposals(
            input: {
              SET: {
                type:          REGULAR_SEMESTER
                semester:      "2025A"
                raLimitStart:  { hms: "12:00:00" }
                raLimitEnd:    { hms: "18:00:00" }
                decLimitStart: { dms: "45:00:00" }
                decLimitEnd:   { dms: "-45:00:00" }
                activeStart:   "2025-02-01 14:00:00"
                activeEnd:     "2025-07-31 14:00:00"
                instruments:   [GMOS_NORTH]
                submissionDeadlineDefault: "2025-07-31 10:00:01"
                partners:      [
                  {
                    partner: CA
                    submissionDeadlineOverride: "2025-07-31 10:00:00"
                  },
                  {
                    partner: US
                  }
                ]
              }
            }
          ) {
            callForProposals {
              id
            }
          }
        }
      """
    ).flatMap {
      _.hcursor
       .downFields("createCallForProposals", "callForProposals", "id")
       .as[CallForProposals.Id]
       .leftMap(f => new RuntimeException(f.message))
       .liftTo[IO]
    }

  test("type") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                type: DEMO_SCIENCE
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                type
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "type": "DEMO_SCIENCE"
                }
              ]
            }
          }
        """.asRight
      )
    }
  }

  test("semester") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                semester: "2024B"
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                semester
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "semester": "2024B"
                }
              ]
            }
          }
        """.asRight
      )
    }
  }

  test("RA limits - both") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                raLimitStart: { hms: "00:00:00" }
                raLimitEnd:   { hms: "06:00:00" }
              }
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                raLimitStart { hms }
                raLimitEnd { hms }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "raLimitStart": { "hms": "00:00:00.000000" },
                  "raLimitEnd":   { "hms": "06:00:00.000000" }
                }
              ]
            }
          }
        """.asRight
      )
    }

  }

  test("RA limits - delete") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                raLimitStart: null
                raLimitEnd:   null
              }
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                raLimitStart { hms }
                raLimitEnd { hms }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "raLimitStart": null,
                  "raLimitEnd":   null
                }
              ]
            }
          }
        """.asRight
      )
    }

  }

  test("RA limits - start only") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                raLimitStart: { hms: "00:00:00" }
              }
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                raLimitStart { hms }
                raLimitEnd { hms }
              }
            }
          }
        """,
        List("Argument 'input.SET' is invalid: Supply both raLimitStart and raLimitEnd or neither").asLeft
      )
    }
  }

  test("RA limits - end only") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                raLimitStart: null
                raLimitEnd:   { hms: "00:00:00" }
              }
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                raLimitStart { hms }
                raLimitEnd { hms }
              }
            }
          }
        """,
        List("Argument 'input.SET' is invalid: Supply both raLimitStart and raLimitEnd or neither").asLeft
      )
    }
  }

  test("Dec limits - both") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                decLimitStart: { dms: "00:00:00" }
                decLimitEnd:   { dms: "00:00:01" }
              }
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                decLimitStart { dms }
                decLimitEnd { dms }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "decLimitStart": { "dms": "+00:00:00.000000" },
                  "decLimitEnd":   { "dms": "+00:00:01.000000" }
                }
              ]
            }
          }
        """.asRight
      )
    }

  }

  test("Dec limits - delete") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                decLimitStart:  null
                decLimitEnd:    null
              }
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                decLimitStart { dms }
                decLimitEnd { dms }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "decLimitStart": null,
                  "decLimitEnd":   null
                }
              ]
            }
          }
        """.asRight
      )
    }

  }

  test("Dec limits - start only") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                decLimitStart: { dms: "00:00:00" }
              }
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                decLimitStart { dms }
                decLimitEnd { dms }
              }
            }
          }
        """,
        List("Argument 'input.SET' is invalid: Supply both decLimitStart and decLimitEnd or neither").asLeft
      )
    }
  }

  test("Dec limits - end only") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                decLimitStart: null
                decLimitEnd:   { dms: "00:00:00" }
              }
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                decLimitStart { dms }
                decLimitEnd { dms }
              }
            }
          }
        """,
        List("Argument 'input.SET' is invalid: Supply both decLimitStart and decLimitEnd or neither").asLeft
      )
    }
  }

  test("active - start and end") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                activeStart: "2024-12-31 14:00:00"
                activeEnd:   "2026-01-01 14:00:00"
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                active {
                  start
                  end
                }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "active": {
                    "start": "2024-12-31 14:00:00",
                    "end":   "2026-01-01 14:00:00"
                  }
                }
              ]
            }
          }
        """.asRight

      )
    }
  }

  test("active - start only") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                activeStart: "2024-12-31 14:00:00"
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                active {
                  start
                  end
                }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "active": {
                    "start": "2024-12-31 14:00:00",
                    "end":   "2025-07-31 14:00:00"
                  }
                }
              ]
            }
          }
        """.asRight

      )
    }
  }

  test("active - end only") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                activeEnd: "2025-06-01 14:00:00"
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                active {
                  start
                  end
                }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "active": {
                    "start": "2025-02-01 14:00:00",
                    "end":   "2025-06-01 14:00:00"
                  }
                }
              ]
            }
          }
        """.asRight

      )
    }
  }

  test("active - end before start, both specified") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                activeStart: "2025-12-31 14:00:00"
                activeEnd:   "2025-01-01 14:00:00"
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                active {
                  start
                  end
                }
              }
            }
          }
        """,
        List("Argument 'input.SET' is invalid: activeStart must come before activeEnd").asLeft
      )
    }
  }

    test("active - end before start, start moved") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                activeStart: "2026-01-01 14:00:00"
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                active {
                  start
                  end
                }
              }
            }
          }
        """,
        List("Requested update to the active period is invalid: activeStart must come before activeEnd").asLeft
      )
    }
  }

  test("active - end before start, end moved") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                activeEnd: "2020-01-01 14:00:00"
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                active {
                  start
                  end
                }
              }
            }
          }
        """,
        List("Requested update to the active period is invalid: activeStart must come before activeEnd").asLeft
      )
    }
  }

  test("instruments") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                instruments: [GMOS_NORTH, GMOS_SOUTH]
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                instruments
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "instruments": [
                    "GMOS_NORTH",
                    "GMOS_SOUTH"
                  ]
                }
              ]
            }
          }
        """.asRight
      )
    }
  }

  test("instruments - delete") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                instruments: null
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                instruments
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "instruments": []
                }
              ]
            }
          }
        """.asRight
      )
    }
  }

  test("instruments - duplicate") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                instruments: [GMOS_SOUTH, GMOS_SOUTH]
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                instruments
              }
            }
          }
        """,
        List("Argument 'input.SET' is invalid: duplicate 'instruments' specified: GMOS_SOUTH").asLeft
      )
    }
  }

  test("partners") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                partners: [
                  {
                    partner: BR
                    submissionDeadlineOverride: "2025-08-15 04:00:00"
                  },
                  {
                    partner: AR
                  }
                ]
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                partners {
                  partner
                  submissionDeadline
                }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "partners": [
                    {
                      "partner": "AR",
                      "submissionDeadline": "2025-07-31 10:00:01"
                    },
                    {
                      "partner": "BR",
                      "submissionDeadline": "2025-08-15 04:00:00"
                    }
                  ]
                }
              ]
            }
          }
        """.asRight
      )
    }
  }

  test("partners - delete") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                partners: null
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                partners { partner }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "partners": []
                }
              ]
            }
          }
        """.asRight
      )
    }
  }

  test("partners - duplicate") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                partners: [
                  {
                    partner: BR
                    submissionDeadlineOverride: "2025-08-15 04:00:00"
                  },
                  {
                    partner: BR
                    submissionDeadlineOverride: "2025-08-15 04:00:00"
                  }
                ]
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                instruments
              }
            }
          }
        """,
        List("Argument 'input.SET' is invalid: duplicate 'partners' specified: BR").asLeft
      )
    }
  }

  test("partners - remove default deadline") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                submissionDeadlineDefault: null
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                partners {
                  partner
                  submissionDeadline
                }
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "partners": [
                    {
                      "partner": "CA",
                      "submissionDeadline": "2025-07-31 10:00:00"
                    },
                    {
                      "partner": "US",
                      "submissionDeadline": null
                    }
                  ]
                }
              ]
            }
          }
        """.asRight
      )
    }
  }

  test("existence") {
    createCall.flatMap { id =>
      expect(
        staff,
        s"""
          mutation {
            updateCallsForProposals(input: {
              SET: {
                existence: DELETED
              },
              WHERE: {
                id: { EQ: "$id" }
              }
            }) {
              callsForProposals {
                existence
              }
            }
          }
        """,
        json"""
          {
            "updateCallsForProposals": {
              "callsForProposals": [
                {
                  "existence": "DELETED"
                }
              ]
            }
          }
        """.asRight
      )
    }
  }
}

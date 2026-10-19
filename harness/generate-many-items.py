"""Writes scenarios/many-items.json: one thread with a few hundred items, for scroll and perf."""

import json
import pathlib
from datetime import datetime, timedelta, timezone

THREAD = "thr_01JC5D0M4NYITEMS"
PROJECT = "prj_01JB2K7Q9V3XKSTORE"
RUN = "run_01JC5D0M5P6Q"
START = datetime(2026, 10, 19, 6, 0, tzinfo=timezone.utc)
ROUNDS = 80


def iso(minutes: float) -> str:
    return (START + timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def base(kind: str, index: int, ordinal: int) -> dict:
    stamp = iso(ordinal * 0.5)
    return {
        "type": kind,
        "id": f"{kind[:3]}_{index:04d}",
        "threadId": THREAD,
        "runId": RUN,
        "ordinal": ordinal,
        "status": "completed",
        "startedAt": stamp,
        "completedAt": stamp,
        "updatedAt": stamp,
    }


def items() -> list:
    out = []
    for i in range(ROUNDS):
        module = ["kstore", "kstore-file", "kstore-storage", "kstore-test"][i % 4]
        out.append({**base("reasoning", i, len(out) + 1),
                    "text": f"Pass {i + 1}: checking `:{module}` for the next failing test."})
        out.append({**base("command_execution", i, len(out) + 1),
                    "input": f"./gradlew :{module}:jvmTest --tests '*Case{i}*'",
                    "output": f"> Task :{module}:jvmTest\nCase{i}Test > roundTrip PASSED\n\nBUILD SUCCESSFUL in {3 + i % 9}s",
                    "exitCode": 0})
        out.append({**base("file_change", i, len(out) + 1),
                    "fileName": f"{module}/src/commonTest/kotlin/Case{i}Test.kt",
                    "additions": 1 + i % 5,
                    "deletions": i % 3,
                    "diffStr": f"@@ -{i + 1},2 +{i + 1},2 @@\n-  val expected = {i}\n+  val expected: Int = {i}\n"})
        out.append({**base("assistant_message", i, len(out) + 1),
                    "text": f"Case {i} passes now. {ROUNDS - i - 1} to go."})
        if i % 8 == 7:
            out.append({**base("system_notice", i, len(out) + 1),
                        "message": f"Context compacted after pass {i + 1}."})
    return out


def main() -> None:
    entries = items()
    scenario = {
        "connection": {"state": "connected"},
        "projects": [{
            "id": PROJECT,
            "title": "KStore",
            "workspaceRoot": "/Users/xxfast/Developer/XXFAST/KMP/KStore",
            "createdAt": "2026-08-02T09:14:00.000Z",
            "updatedAt": iso(len(entries) * 0.5),
        }],
        "threads": [{
            "id": THREAD,
            "projectId": PROJECT,
            "title": "Fix every flaky test in the JVM suite",
            "status": "completed",
            "latestRunId": RUN,
            "itemCount": len(entries),
            "createdAt": iso(0),
            "updatedAt": iso(len(entries) * 0.5),
        }],
        "runs": {THREAD: [{
            "id": RUN,
            "ordinal": 1,
            "status": "completed",
            "requestedAt": iso(0),
            "startedAt": iso(0),
            "completedAt": iso(len(entries) * 0.5),
        }]},
        "items": {THREAD: entries},
    }
    target = pathlib.Path(__file__).resolve().parent / "scenarios" / "many-items.json"
    target.write_text(json.dumps(scenario, indent=2) + "\n")
    print(f"wrote {len(entries)} items to {target}")


if __name__ == "__main__":
    main()

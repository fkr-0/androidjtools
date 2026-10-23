"""The S5 analysis contract as the fake speaks it: hello capabilities, the analysis pull scope, the explicit request
route and the analysis_suggestion.accept mutation (see docs/superpowers/plans/2026-10-23-samplelab-s5-analysis-suggestions.md)."""

from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request

from fake_samplelib_server import ACCESS_TOKEN, PAIRING_CODE, SampleLibFakeHttpServer

HELLO = {"type": "hello_request", "protocol_versions": ["1"], "client_id": "android-client", "installation_id": "installation-a", "capabilities": []}
ACCEPT = "analysis_suggestion.accept"


class AnalysisFakeTests(unittest.TestCase):
    def setUp(self) -> None:
        self.server = SampleLibFakeHttpServer(("127.0.0.1", 0))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        host, port = self.server.server_address
        self.base = f"http://{host}:{port}"
        self.engine = self.server.engine
        self.counter = 0

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    # ---- plumbing

    def call(self, path: str, body: dict | None = None, *, auth: bool = True) -> tuple[int, dict]:
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = f"Bearer {ACCESS_TOKEN}"
        request = urllib.request.Request(self.base + path, data=json.dumps(body or {}).encode(), headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read().decode())
        except urllib.error.HTTPError as exc:
            payload = json.loads(exc.read().decode() or "{}")
            exc.close()
            return exc.code, payload

    def pair(self) -> None:
        status, _ = self.call(
            "/v1/sync/pair",
            {"type": "pairing_request", "client_id": "android-client", "installation_id": "installation-a", "pairing_code": PAIRING_CODE},
            auth=False,
        )
        self.assertEqual(200, status)

    def hello(self) -> dict:
        status, body = self.call("/v1/sync/hello", HELLO)
        self.assertEqual(200, status)
        return body["capabilities"]

    def pull(self, scopes: list[str], cursor: str | None = None) -> tuple[int, dict]:
        return self.call("/v1/sync/pull", {"type": "pull_request", "protocol_version": "1", "cursor": cursor, "scopes": scopes, "limit": 100})

    def suggestions(self, scopes: list[str] | None = None) -> dict[str, dict]:
        status, page = self.pull(scopes or ["analysis"])
        self.assertEqual(200, status)
        return {c["entity_id"]: c for c in page["changes"] if c["entity_type"] == "analysis_suggestion"}

    def push(self, mutation: dict) -> dict:
        status, body = self.call("/v1/sync/push", {"type": "push_request", "protocol_version": "1", "client_id": "android-client", "mutations": [mutation]})
        self.assertEqual(200, status)
        return body["receipts"][0]

    def accept(self, suggestion: dict, payload: dict | None = None, **overrides) -> dict:
        self.counter += 1
        mutation = {
            "mutation_id": f"accept-{self.counter:04d}",
            "entity_type": "analysis_suggestion",
            "entity_id": suggestion["entity_id"],
            "base_revision": suggestion["revision"],
            "operation": ACCEPT,
            "payload": payload if payload is not None else {"target_interval_id": "interval-full-1"},
            "created_at": "2026-10-02T12:00:00Z",
            "provenance": {"source": "android_accept"},
        }
        mutation.update(overrides)
        return self.push(mutation)

    def interval(self) -> dict:
        _, page = self.pull(["library"])
        return next(c for c in page["changes"] if c["entity_type"] == "interval" and c["entity_id"] == "interval-full-1")

    def by_kind(self, kind: str) -> dict:
        return next(s for s in self.suggestions().values() if s["value"]["kind"] == kind)

    # ---- hello

    def test_hello_advertises_the_kinds_the_request_mode_and_the_accept_operation(self) -> None:
        self.pair()
        caps = self.hello()
        self.assertEqual(["tempo", "key", "beat_grid", "cue_candidates"], caps["analysis"])
        self.assertEqual("enqueue_v1", caps["analysis_request"])
        self.assertIn(ACCEPT, caps["mutations"])
        self.assertNotIn("analysis.suggestion.accept", caps["mutations"])

    def test_without_a_provider_the_request_mode_says_unavailable_but_suggestions_stay_readable(self) -> None:
        self.pair()
        self.call("/__samplelib__/analysis/configure", {"enabled": True, "request": "unavailable"})
        caps = self.hello()
        self.assertEqual("unavailable", caps["analysis_request"])
        self.assertTrue(caps["analysis"])

    def test_an_older_server_has_an_empty_analysis_list_no_request_key_and_no_accept(self) -> None:
        self.pair()
        self.call("/__samplelib__/analysis/configure", {"enabled": False})
        caps = self.hello()
        self.assertEqual([], caps["analysis"])
        self.assertNotIn("analysis_request", caps)
        self.assertNotIn(ACCEPT, caps["mutations"])
        status, _ = self.pull(["library", "analysis"])
        self.assertEqual(400, status)

    # ---- pull

    def test_suggestions_have_the_documented_envelope_and_data(self) -> None:
        self.pair()
        found = self.suggestions()
        self.assertEqual(
            {"run-1:tempo:asset-1", "run-1:key:asset-1", "run-1:beat_grid:asset-1", "run-1:cue_candidates:asset-1"}, set(found)
        )
        tempo = found["run-1:tempo:asset-1"]
        self.assertEqual("sample-lib.analysis-suggestion/v1", tempo["schema"])
        self.assertTrue(tempo["revision"])
        value = tempo["value"]
        self.assertEqual(
            {"id", "kind", "asset_id", "interval_id", "analysis_run_id", "tool", "tool_version", "created_at", "confidence", "data"}, set(value)
        )
        self.assertEqual("run-1:tempo:asset-1", value["id"])
        self.assertIsNone(value["interval_id"])
        self.assertEqual({"bpm": 92.48, "alternatives": [46.24, 184.96]}, value["data"])
        self.assertEqual({"key_label": "A minor", "root": "A", "mode": "minor"}, found["run-1:key:asset-1"]["value"]["data"])
        grid = found["run-1:beat_grid:asset-1"]["value"]["data"]
        self.assertEqual(grid["beat_times_ms"][0], grid["first_beat_ms"])
        points = found["run-1:cue_candidates:asset-1"]["value"]["data"]["points_ms"]
        self.assertEqual(sorted(set(points)), points)

    def test_a_run_without_confidence_still_yields_a_suggestion(self) -> None:
        self.pair()
        self.assertIsNone(self.suggestions()["run-1:key:asset-1"]["value"]["confidence"])
        self.assertIsNotNone(self.suggestions()["run-1:tempo:asset-1"]["value"]["confidence"])

    def test_the_library_scope_never_carries_suggestions_and_both_scopes_can_be_pulled_together(self) -> None:
        self.pair()
        _, library = self.pull(["library"])
        self.assertFalse([c for c in library["changes"] if c["entity_type"] == "analysis_suggestion"])
        self.assertTrue([c for c in library["changes"] if c["entity_type"] == "interval"])
        _, both = self.pull(["library", "analysis"])
        types = {c["entity_type"] for c in both["changes"]}
        self.assertLessEqual({"asset", "interval", "analysis_suggestion"}, types)
        _, only = self.pull(["analysis"])
        self.assertEqual({"analysis_suggestion"}, {c["entity_type"] for c in only["changes"]})

    def test_the_analysis_scope_needs_analysis_read(self) -> None:
        self.pair()
        self.call("/__samplelib__/deny-analysis")
        status, body = self.pull(["library", "analysis"])
        self.assertEqual(403, status)
        self.assertEqual("scope_forbidden", body["error"]["code"])
        status, _ = self.pull(["library"])
        self.assertEqual(200, status)

    def test_hello_and_pull_never_start_analysis(self) -> None:
        self.pair()
        self.hello()
        self.pull(["library", "analysis"])
        self.pull(["analysis"])
        self.assertEqual(0, self.engine.provider_calls)
        self.assertEqual([], self.engine.analysis_jobs)

    # ---- request

    def request(self, body: dict, *, auth: bool = True) -> tuple[int, dict]:
        return self.call("/v1/sync/analysis/request", body, auth=auth)

    def test_a_request_for_cached_kinds_starts_nothing(self) -> None:
        self.pair()
        status, body = self.request({"asset_id": "asset-1"})
        self.assertEqual(200, status)
        self.assertEqual("cached", body["status"])
        self.assertEqual({"tempo": "cached", "key": "cached", "beat_grid": "cached", "cue_candidates": "cached"}, body["kinds"])
        self.assertIsNone(body["job_id"])
        self.assertEqual(0, self.engine.provider_calls)

    def test_a_request_for_missing_results_queues_one_job_and_a_second_request_joins_it(self) -> None:
        self.pair()
        self.call("/__samplelib__/analysis/replace", {"asset_id": "asset-2", "kind": "tempo"})  # asset-2 now has tempo only
        status, first = self.request({"asset_id": "asset-2"})
        self.assertEqual(200, status)
        self.assertEqual("queued", first["status"])
        self.assertEqual("cached", first["kinds"]["tempo"])
        self.assertEqual("queued", first["kinds"]["key"])
        self.assertTrue(first["job_id"])
        _, second = self.request({"asset_id": "asset-2"})
        self.assertEqual(first["job_id"], second["job_id"])
        self.assertEqual(1, len(self.engine.analysis_jobs))
        self.call("/__samplelib__/analysis/running")
        _, third = self.request({"asset_id": "asset-2"})
        self.assertEqual("running", third["status"])
        self.assertEqual(first["job_id"], third["job_id"])

    def test_results_arrive_with_the_next_pull_and_a_rerun_replaces_the_old_id(self) -> None:
        self.pair()
        _, page = self.pull(["library", "analysis"])
        cursor = page["next_cursor"]
        self.request({"asset_id": "asset-1", "kinds": ["tempo"], "force": True})
        self.call("/__samplelib__/analysis/complete")
        _, delta = self.pull(["analysis"], cursor)
        self.assertEqual("incremental", delta["mode"])
        self.assertEqual(["run-1:tempo:asset-1"], [t["entity_id"] for t in delta["tombstones"]])
        new = [c for c in delta["changes"] if c["entity_type"] == "analysis_suggestion"]
        self.assertEqual(1, len(new))
        self.assertNotEqual("run-1:tempo:asset-1", new[0]["entity_id"])
        self.assertTrue(new[0]["entity_id"].endswith(":tempo:asset-1"))

    def test_without_a_provider_the_answer_is_unavailable_with_a_message_and_http_200(self) -> None:
        self.pair()
        self.call("/__samplelib__/analysis/configure", {"enabled": True, "request": "unavailable"})
        status, body = self.request({"asset_id": "asset-1"})
        self.assertEqual(200, status)
        self.assertEqual("unavailable", body["status"])
        self.assertTrue(body["message"])
        self.assertEqual(0, self.engine.provider_calls)

    def test_unknown_asset_unknown_kind_and_bad_bodies(self) -> None:
        self.pair()
        status, body = self.request({"asset_id": "nope"})
        self.assertEqual(404, status)
        self.assertEqual("entity_not_found", body["detail"]["error"]["code"])
        _, body = self.request({"asset_id": "asset-1", "kinds": ["tempo", "stems"]})
        self.assertEqual("unavailable", body["kinds"]["stems"])
        self.assertEqual("cached", body["kinds"]["tempo"])
        self.assertEqual(400, self.request({})[0])
        self.assertEqual(400, self.request({"asset_id": "asset-1", "kinds": "tempo"})[0])
        self.assertEqual(400, self.request({"asset_id": "asset-1", "force": "yes"})[0])

    def test_a_request_needs_a_credential(self) -> None:
        self.pair()
        self.assertEqual(401, self.request({"asset_id": "asset-1"}, auth=False)[0])

    # ---- accept

    def test_accepting_a_tempo_sets_the_intervals_bpm_and_the_next_pull_delivers_it(self) -> None:
        self.pair()
        _, page = self.pull(["library", "analysis"])
        before = self.interval()
        self.assertEqual(88.0, before["value"]["interval"]["bpm"])
        receipt = self.accept(self.by_kind("tempo"))
        self.assertEqual("applied", receipt["outcome"])
        self.assertEqual(92.48, receipt["canonical"]["interval"]["bpm"])
        self.assertEqual(0.82, receipt["canonical"]["interval"]["bpm_confidence"])
        after = self.interval()
        self.assertEqual(self.by_kind("tempo")["revision"], receipt["entity_revision"], "entity_revision is the suggestion's")
        self.assertEqual(after["revision"], receipt["canonical"]["revision"], "the interval's new revision")
        self.assertEqual(receipt["mutation_id"], receipt["canonical"]["mutation_id"])
        self.assertEqual([], receipt["canonical"]["markers"])
        self.assertNotEqual(before["revision"], after["revision"])
        self.assertEqual(92.48, after["value"]["interval"]["bpm"])
        _, delta = self.pull(["library", "analysis"], page["next_cursor"])
        self.assertEqual(["interval-full-1"], [c["entity_id"] for c in delta["changes"] if c["entity_type"] == "interval"])
        self.assertEqual("android_accept", self.engine.bpm_hypotheses[0]["source_kind"])
        self.assertEqual(0, self.engine.bpm_hypotheses[0]["requires_human_review"])

    def test_a_tempo_alternative_can_be_chosen_and_nothing_else_can(self) -> None:
        self.pair()
        tempo = self.by_kind("tempo")
        self.assertEqual("applied", self.accept(tempo, {"target_interval_id": "interval-full-1", "bpm": 46.24})["outcome"])
        self.assertEqual(46.24, self.interval()["value"]["interval"]["bpm"])
        bad = self.accept(tempo, {"target_interval_id": "interval-full-1", "bpm": 100.0})
        self.assertEqual("rejected", bad["outcome"])
        self.assertEqual("invalid_payload", bad["error"]["code"])

    def test_a_key_sets_label_root_and_mode_and_a_repeat_is_a_no_op(self) -> None:
        self.pair()
        key = self.by_kind("key")
        applied = self.accept(key)
        self.assertEqual("applied", applied["outcome"])
        interval = self.interval()["value"]["interval"]
        self.assertEqual(("A minor", "A", "minor"), (interval["key_label"], interval["root"], interval["mode"]))
        self.assertNotIn("bpm_confidence", interval)  # this run recorded no confidence, so none is invented
        again = self.accept(key)
        self.assertEqual("no_op", again["outcome"])
        self.assertEqual(key["revision"], again["entity_revision"])
        self.assertEqual(self.interval()["revision"], again["canonical"]["revision"])

    def test_a_beat_grid_sets_the_bpm_only_and_takes_no_bpm_choice(self) -> None:
        self.pair()
        grid = self.by_kind("beat_grid")
        refused = self.accept(grid, {"target_interval_id": "interval-full-1", "bpm": 92.48})
        self.assertEqual("invalid_payload", refused["error"]["code"])
        receipt = self.accept(grid)
        self.assertEqual("applied", receipt["outcome"])
        interval = self.interval()["value"]["interval"]
        self.assertEqual(92.48, interval["bpm"])
        self.assertNotIn("first_beat_ms", interval)

    def test_cue_candidates_are_not_accepted_here(self) -> None:
        self.pair()
        receipt = self.accept(self.by_kind("cue_candidates"))
        self.assertEqual("rejected", receipt["outcome"])
        self.assertEqual("use_editor_state_replace", receipt["error"]["code"])

    def test_the_target_interval_rules(self) -> None:
        self.pair()
        tempo = self.by_kind("tempo")
        self.assertEqual("target_interval_required", self.accept(tempo, {})["error"]["code"])
        self.assertEqual("entity_not_found", self.accept(tempo, {"target_interval_id": "nope"})["error"]["code"])
        self.assertEqual("base_revision_required", self.accept(tempo, base_revision=None)["error"]["code"])
        self.assertEqual("entity_type_mismatch", self.accept(tempo, entity_type="interval")["error"]["code"])
        self.assertEqual("entity_not_found", self.accept(tempo, entity_id="run-9:tempo:asset-1")["error"]["code"])

    def test_a_stale_base_revision_is_a_conflict_with_the_current_suggestion(self) -> None:
        self.pair()
        tempo = self.by_kind("tempo")
        self.call("/__samplelib__/analysis/replace", {"asset_id": "asset-1", "kind": "tempo"})
        removed = self.accept(tempo)
        self.assertEqual("conflict", removed["outcome"])
        self.assertEqual("suggestion_changed", removed["conflict"]["code"])
        self.assertIsNone(removed["conflict"]["remote_value"])
        self.assertIsNone(removed["canonical"])
        self.assertIsNone(removed["entity_revision"])
        self.assertEqual("analysis_suggestion", removed["conflict"]["merge_class"])
        self.assertEqual(88.0, self.interval()["value"]["interval"]["bpm"])

        replacement = self.by_kind("tempo")
        stale = dict(replacement, revision="rev:stale")
        changed = self.accept(stale)
        self.assertEqual("conflict", changed["outcome"])
        self.assertEqual(replacement["entity_id"], changed["conflict"]["remote_value"]["id"])
        self.assertEqual(replacement["revision"], changed["conflict"]["authoritative_revision"])
        self.assertEqual(replacement["revision"], changed["entity_revision"])
        self.assertEqual(replacement["entity_id"], changed["canonical"]["id"])

    def test_the_same_mutation_is_idempotent_and_a_reused_id_with_another_body_is_refused(self) -> None:
        self.pair()
        tempo = self.by_kind("tempo")
        first = self.accept(tempo, mutation_id="accept-idem-1")
        replay = self.accept(tempo, mutation_id="accept-idem-1")
        self.assertEqual(first, replay)
        self.assertEqual(1, len(self.engine.bpm_hypotheses))
        clash = self.accept(tempo, {"target_interval_id": "interval-full-1", "bpm": 46.24}, mutation_id="accept-idem-1")
        self.assertEqual("rejected", clash["outcome"])
        self.assertEqual("idempotency_key_reused", clash["error"]["code"])

    def test_the_other_mutation_families_still_work(self) -> None:
        self.pair()
        receipt = self.push(
            {
                "mutation_id": "meta-0001", "entity_type": "asset", "entity_id": "asset-1", "base_revision": "rev:asset-1:7",
                "operation": "asset.metadata.patch", "payload": {"rating": 4}, "created_at": "2026-10-02T12:00:00Z", "provenance": {},
            }
        )
        self.assertEqual("applied", receipt["outcome"])


if __name__ == "__main__":
    unittest.main()

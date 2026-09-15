# Sample Lib sync fake server

This directory is the executable test double for the synthesized, still-unshipped `contracts/sync/v1` protocol.

Run the focused contract/server suite:

    PYTHONDONTWRITEBYTECODE=1 PYTHONWARNINGS='error::ResourceWarning' python3 -m unittest discover -s test-server -p 'test_*.py' -v

Run the deterministic HTTP fake:

    python3 test-server/fake_sync_server.py --scenario compatible --port 8765

The v1 HTTP facade is intentionally namespaced:

    POST /v1/sync/hello
    POST /v1/sync/pull
    POST /v1/sync/push
    GET  /v1/sync/receipts/{mutation_id}

Change scenarios without restarting by POSTing e.g. `{"state":"conflict"}` to `/__scenario__`. Scenario names come from `contracts/sync/v1/protocol.json`; tests prove every state is driven, including explicit cursor expiry.

The fake separates opaque entity revisions (`rev:...`) from its numeric internal `server_change_revision`. It advertises mutation capability by operation family and deliberately does not advertise playlist writes until Sample Lib has canonical playlist/item identity.

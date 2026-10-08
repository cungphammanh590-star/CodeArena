"""Wire-level checks for the Python half of the internal gRPC contract."""

from concurrent.futures import ThreadPoolExecutor
import json

import grpc
import pytest

from app import internal_pb2, internal_pb2_grpc
from app.config import Settings
from app.services import internal_rpc


class FakeBusiness(internal_pb2_grpc.InternalBusinessServicer):
    def __init__(self) -> None:
        self.calls = []

    def _reply(self, name, request, context):
        self.calls.append((name, request, dict(context.invocation_metadata())))
        return internal_pb2.JsonReply(json=json.dumps({"ok": True, "method": name}))

    def ExecuteTool(self, request, context):
        return self._reply("tool", request, context)

    def GetUserLlm(self, request, context):
        return self._reply("user", request, context)

    def RecordUsage(self, request, context):
        return self._reply("usage", request, context)


@pytest.fixture
def business():
    service = FakeBusiness()
    server = grpc.server(ThreadPoolExecutor(max_workers=4))
    internal_pb2_grpc.add_InternalBusinessServicer_to_server(service, server)
    port = server.add_insecure_port("127.0.0.1:0")
    server.start()
    try:
        yield service, Settings(business_grpc_target=f"127.0.0.1:{port}", internal_tool_token="test-secret")
    finally:
        server.stop(grace=0).wait()


def test_sync_calls_preserve_payload_and_token(business):
    service, settings = business
    result = internal_rpc.execute_tool_sync(
        settings,
        tool_name="bind_problem",
        params={"problem_id": 215},
        session_id="s1",
        problem_id=215,
        user_public_id="u1",
    )
    assert result == {"ok": True, "method": "tool"}
    assert internal_rpc.get_user_llm(settings, "u1", 2) == {"ok": True, "method": "user"}
    assert internal_rpc.record_usage(settings, "u1", {"total_tokens": 12}) == {
        "ok": True, "method": "usage"
    }
    _, tool, metadata = service.calls[0]
    assert metadata["x-internal-token"] == "test-secret"
    assert tool.user_public_id == "u1"
    assert tool.session_id == "s1"
    assert tool.has_problem_id and tool.problem_id == 215
    assert json.loads(tool.params_json) == {"problem_id": 215}
    assert json.loads(service.calls[2][1].payload_json) == {"total_tokens": 12}


@pytest.mark.asyncio
async def test_async_tool_call_uses_same_contract(business):
    service, settings = business
    result = await internal_rpc.execute_tool_async(
        settings,
        tool_name="get_session_binding",
        params={},
        session_id="s2",
        problem_id=None,
        user_public_id="u2",
    )
    assert result["method"] == "tool"
    assert service.calls[0][1].has_problem_id is False

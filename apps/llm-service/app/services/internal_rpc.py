"""gRPC transport for private llm-service → business-service calls."""

from __future__ import annotations

import json
from functools import lru_cache
from typing import Any

import grpc

from app import internal_pb2, internal_pb2_grpc
from app.config import Settings
from app.observability.request_context import get_request_id


@lru_cache(maxsize=8)
def _sync_channel(target: str) -> grpc.Channel:
    return grpc.insecure_channel(target)


def _metadata(settings: Settings) -> tuple[tuple[str, str], ...]:
    return (("x-internal-token", settings.internal_tool_token),)


def _decode(reply: internal_pb2.JsonReply) -> dict[str, Any]:
    data = json.loads(reply.json)
    if not isinstance(data, dict):
        raise ValueError("invalid internal gRPC response")
    return data


def execute_tool_sync(
    settings: Settings,
    *,
    tool_name: str,
    params: dict[str, Any],
    session_id: str,
    problem_id: int | None,
    user_public_id: str,
) -> dict[str, Any]:
    stub = internal_pb2_grpc.InternalBusinessStub(_sync_channel(settings.business_grpc_target))
    request = internal_pb2.ToolRequest(
        user_public_id=user_public_id,
        tool_name=tool_name,
        params_json=json.dumps(params, ensure_ascii=False),
        session_id=session_id,
        problem_id=problem_id or 0,
        has_problem_id=problem_id is not None,
        request_id=get_request_id() or "",
    )
    return _decode(stub.ExecuteTool(
        request, timeout=settings.llm_timeout_seconds, metadata=_metadata(settings)
    ))


async def execute_tool_async(
    settings: Settings,
    *,
    tool_name: str,
    params: dict[str, Any],
    session_id: str,
    problem_id: int | None,
    user_public_id: str,
) -> dict[str, Any]:
    request = internal_pb2.ToolRequest(
        user_public_id=user_public_id,
        tool_name=tool_name,
        params_json=json.dumps(params, ensure_ascii=False),
        session_id=session_id,
        problem_id=problem_id or 0,
        has_problem_id=problem_id is not None,
        request_id=get_request_id() or "",
    )
    async with grpc.aio.insecure_channel(settings.business_grpc_target) as channel:
        stub = internal_pb2_grpc.InternalBusinessStub(channel)
        return _decode(await stub.ExecuteTool(
            request, timeout=settings.llm_timeout_seconds, metadata=_metadata(settings)
        ))


def get_user_llm(settings: Settings, user_public_id: str, timeout: float) -> dict[str, Any]:
    stub = internal_pb2_grpc.InternalBusinessStub(_sync_channel(settings.business_grpc_target))
    request = internal_pb2.UserRequest(
        user_public_id=user_public_id, request_id=get_request_id() or ""
    )
    return _decode(stub.GetUserLlm(request, timeout=timeout, metadata=_metadata(settings)))


def record_usage(settings: Settings, user_public_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    stub = internal_pb2_grpc.InternalBusinessStub(_sync_channel(settings.business_grpc_target))
    request = internal_pb2.UsageRequest(
        user_public_id=user_public_id,
        payload_json=json.dumps(payload, ensure_ascii=False),
        request_id=get_request_id() or "",
    )
    return _decode(stub.RecordUsage(request, timeout=5.0, metadata=_metadata(settings)))

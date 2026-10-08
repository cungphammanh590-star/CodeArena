"""Private gRPC health probe used by business-service."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

import grpc

from app import internal_pb2, internal_pb2_grpc
from app.config import Settings


class _Health(internal_pb2_grpc.LlmHealthServicer):
    def Check(self, request, context):  # noqa: N802
        return internal_pb2.HealthReply(ready=True)


def start_grpc_health(settings: Settings) -> tuple[grpc.Server, int]:
    server = grpc.server(ThreadPoolExecutor(max_workers=2))
    internal_pb2_grpc.add_LlmHealthServicer_to_server(_Health(), server)
    address = f"{settings.llm_grpc_host}:{settings.llm_grpc_port}"
    port = server.add_insecure_port(address)
    server.start()
    return server, port

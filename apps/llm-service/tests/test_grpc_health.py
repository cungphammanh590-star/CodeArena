import grpc

from app import internal_pb2, internal_pb2_grpc
from app.config import Settings
from app.services.grpc_health import start_grpc_health


def test_llm_health_is_available_over_grpc():
    server, port = start_grpc_health(Settings(llm_grpc_host="127.0.0.1", llm_grpc_port=0))
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            reply = internal_pb2_grpc.LlmHealthStub(channel).Check(
                internal_pb2.HealthRequest(), timeout=2
            )
            assert reply.ready
    finally:
        server.stop(grace=0).wait()

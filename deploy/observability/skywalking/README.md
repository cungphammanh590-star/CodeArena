# SkyWalking Java Agent

```bash
make obs-agents   # downloads 9.7.0 into ./agent/
```

Then:

- Local: `make gateway-sw` / `make business-sw`
- Compose: set `GATEWAY_JAVA_TOOL_OPTIONS=-javaagent:/skywalking/skywalking-agent.jar` (volume mounts `./agent` → `/skywalking`)

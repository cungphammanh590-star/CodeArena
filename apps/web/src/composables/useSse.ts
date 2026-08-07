export type SseHandler = (event: string, data: Record<string, unknown>) => void;

/**
 * Read a fetch Response body as Server-Sent Events.
 */
export async function readSse(
  response: Response,
  onEvent: SseHandler,
): Promise<void> {
  const reader = response.body?.getReader();
  if (!reader) return;

  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split("\n\n");
    buffer = parts.pop() || "";
    for (const block of parts) {
      let event = "message";
      let dataLine = "";
      for (const line of block.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        if (line.startsWith("data:")) dataLine += line.slice(5).trim();
      }
      if (!dataLine) continue;
      try {
        onEvent(event, JSON.parse(dataLine) as Record<string, unknown>);
      } catch {
        /* ignore malformed chunks */
      }
    }
  }
}

export function useSse() {
  return { readSse };
}

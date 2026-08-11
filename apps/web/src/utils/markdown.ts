import { marked } from "marked";
import DOMPurify from "dompurify";

marked.setOptions({
  gfm: true,
  breaks: true,
});

/** Render markdown to sanitized HTML for chat bubbles. */
export function renderMarkdown(source: string): string {
  const raw = String(source || "");
  if (!raw.trim()) return "";
  const html = marked.parse(raw, { async: false }) as string;
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
  });
}

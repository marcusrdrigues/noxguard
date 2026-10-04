// @ts-check
// noxeval against the example app, the same way it runs against Nox: over HTTP, as a black box.
// Start the app first (mvn -f examples/chat-spring-boot spring-boot:run), then: npm ci && npx noxeval run
import { defineConfig, httpTarget } from "noxeval";

export default defineConfig({
  target: httpTarget({
    url: process.env.CHAT_URL ?? "http://localhost:8080/api/chat/complete",
    answerPath: "answer",
    contextPath: "context",
    toolCallsPath: "toolCalls",
    name: "noxguard example (scripted model)",
  }),
  cases: "./noxeval.cases.json",
  checks: {
    allowedLinks: ["example.com"],
    leakMarkers: ["You are Ava", "<context>", "<question>", "<history>"],
    // The app caps answers at 1,200 characters and marks the cut with an ellipsis.
    maxLength: 1250,
  },
});

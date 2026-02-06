# Introduction to krukow/copilot-sdk

`krukow/copilot-sdk` is a Clojure SDK for programmatic control of the GitHub Copilot CLI server via JSON-RPC.
It provides a small, idiomatic surface for creating sessions, sending prompts, handling tool calls, and
subscribing to streaming events using core.async.

## What you can do

- Start and stop a Copilot client (stdio or TCP)
- Create and resume sessions
- Send messages synchronously (`send-and-wait!`) or asynchronously (`send!`, `send-async`)
- Subscribe to session events for streaming and orchestration
- Define tools with JSON Schema and handle tool calls in Clojure

## Quick start

```clojure
(require '[krukow.copilot-sdk :as copilot])

(def client (copilot/client {:log-level :info}))
(copilot/start! client)

(def session (copilot/create-session client {:model "gpt-5.2"}))
(def response (copilot/send-and-wait! session {:prompt "What is 2+2?"}))
(println (get-in response [:data :content]))

(copilot/destroy! session)
(copilot/stop! client)
```

## Events and streaming

Sessions emit events on a core.async mult. You can tap it to stream deltas, tool execution events,
or wait for `session.idle` to know processing is complete.

```clojure
(require '[clojure.core.async :refer [chan tap go-loop <!]])

(let [ch (chan 100)]
  (tap (copilot/events session) ch)
  (go-loop []
    (when-let [event (<! ch)]
      (println (:type event))
      (recur))))
```

## Tools

Define tools with JSON Schema and provide handlers:

```clojure
(def lookup-tool
  (copilot/define-tool "lookup"
    {:description "Lookup a value"
     :parameters {:type "object"
                  :properties {:id {:type "string"}}
                  :required ["id"]}
     :handler (fn [{:keys [id]} _]
                (copilot/result-success (str "value=" id))) }))
```

## Where to go next

- See `README.md` for the full API and examples
- See `doc/getting-started.md` for a step-by-step tutorial
- See `doc/auth/` for authentication and BYOK guides
- See `doc/mcp/` for MCP server integration
- Browse `examples/` for working applications
- Generate API docs with `bb docs` (output in `doc/api/`)

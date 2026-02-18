# Getting Started with the Copilot SDK for Clojure

In this tutorial, you'll use the Copilot SDK for Clojure to build a command-line assistant. You'll start with the basics, add streaming responses, then add custom tools — giving Copilot the ability to call your code.

**What you'll build:**

```
Q: What's the weather like in Seattle?
🤖: Let me check the weather for Seattle...
    Currently 62°F and cloudy with a chance of rain.

Q: How about Tokyo?
🤖: In Tokyo it's 75°F and sunny. Great day to be outside!
```

## Prerequisites

Before you begin, make sure you have:

- **GitHub Copilot CLI** installed and authenticated ([Installation guide](https://docs.github.com/en/copilot/how-tos/set-up/install-copilot-cli))
- **Clojure** 1.11+ with the `clojure` CLI tool
- **Java** 11+

Verify the CLI is working:

```bash
copilot --version
```

## Step 1: Add the SDK Dependency

Add to your `deps.edn`:

```clojure
{:deps {io.github.copilot-community-sdk/copilot-sdk-clojure {:mvn/version "LATEST"}}}
```

Or use as a Git dependency:

```clojure
{:deps {io.github.copilot-community-sdk/copilot-sdk-clojure
        {:git/url "https://github.com/copilot-community-sdk/copilot-sdk-clojure"
         :git/sha "LATEST_SHA"}}}
```

## Step 2: Send Your First Message

The simplest way to use the SDK — about 3 lines of code:

```clojure
(require '[github.copilot-sdk.helpers :as h])

(println (h/query "What is 2 + 2?"))
;; => "4"
```

That's it! The helpers API manages the client lifecycle automatically.

For more control, use the explicit client/session API:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client-session [session {:model "gpt-5.2"}]
  (let [response (copilot/send-and-wait! session {:prompt "What is 2 + 2?"})]
    (println (get-in response [:data :content]))))
```

**You should see:**

```
4
```

Congratulations! You just built your first Copilot-powered app in Clojure.

## Step 3: Add Streaming Responses

Right now, you wait for the complete response before seeing anything. Let's make it interactive by streaming the response as it's generated.

### Using Lazy Sequences

```clojure
(require '[github.copilot-sdk.helpers :as h])

(defmulti handle-event :type)
(defmethod handle-event :default [_] nil)
(defmethod handle-event :copilot/assistant.message_delta [{{:keys [delta-content]} :data}]
  (print delta-content)
  (flush))
(defmethod handle-event :copilot/session.idle [_]
  (println))

(run! handle-event (h/query-seq! "Tell me a short joke" :session {:streaming? true}))
```

### Using core.async Channels

```clojure
(require '[clojure.core.async :refer [chan tap go-loop <!]])
(require '[github.copilot-sdk :as copilot])

(copilot/with-client-session [session {:model "gpt-5.2" :streaming? true}]
  (let [ch (chan 256)
        done (promise)]
    (tap (copilot/events session) ch)
    (go-loop []
      (when-let [event (<! ch)]
        (case (:type event)
          :copilot/assistant.message_delta
          (do (print (get-in event [:data :delta-content]))
              (flush))

          :copilot/session.idle
          (do (println)
              (deliver done true))
          nil)
        (recur)))
    (copilot/send! session {:prompt "Tell me a short joke"})
    @done))
```

Run the code and you'll see the response appear word by word.

### Async Session Creation

Use `<create-session` and `<send!` for fully non-blocking operations inside `go` blocks:

```clojure
(require '[clojure.core.async :refer [go <! <!!]])
(require '[github.copilot-sdk :as copilot])

(copilot/with-client [client]
  (let [result-ch
        (go
          (let [session (<! (copilot/<create-session client {:model "gpt-5.2"}))]
            (when (instance? Throwable session)
              (throw session))
            (let [answer (<! (copilot/<send! session {:prompt "Capital of France?"}))]
              answer)))]
    (println (<!! result-ch))))
```

This pattern parks (instead of blocking) on the core.async thread pool, enabling true parallelism across multiple `go` blocks — ideal for multi-agent orchestration. See the [multi-agent example](../examples/README.md) for a complete walkthrough.

### Event Subscription Methods

The SDK uses core.async `mult/tap` for event subscription:

| Function | Description |
|----------|-------------|
| `(copilot/events session)` | Get the core.async mult for all events |
| `(copilot/subscribe-events session)` | Get a tapped channel (convenience) |
| `(copilot/unsubscribe-events session ch)` | Untap a channel |
| `(copilot/events->chan session opts)` | Advanced: custom buffer, transducer |

## Step 4: Add a Custom Tool

Now for the powerful part. Let's give Copilot the ability to call your code by defining a custom tool:

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

;; Define a tool that Copilot can call
(def get-weather
  (copilot/define-tool "get_weather"
    {:description "Get the current weather for a city"
     :parameters {:type "object"
                  :properties {:city {:type "string"
                                      :description "The city name"}}
                  :required ["city"]}
     :handler (fn [{:keys [city]} _invocation]
                ;; In a real app, you'd call a weather API here
                (let [conditions ["sunny" "cloudy" "rainy" "partly cloudy"]
                      temp (+ 50 (rand-int 30))
                      condition (rand-nth conditions)]
                  (copilot/result-success
                   (str city ": " temp "°F and " condition))))}))

(copilot/with-client-session [session {:model "gpt-5.2"
                                       :tools [get-weather]}]
  (println (h/query "What's the weather like in Seattle and Tokyo?"
                    :session session)))
```

Run it and you'll see Copilot call your tool to get weather data, then respond with the results!

## Step 5: Build an Interactive Assistant

Let's put it all together into an interactive assistant:

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

(def get-weather
  (copilot/define-tool "get_weather"
    {:description "Get the current weather for a city"
     :parameters {:type "object"
                  :properties {:city {:type "string" :description "The city name"}}
                  :required ["city"]}
     :handler (fn [{:keys [city]} _]
                (let [temp (+ 50 (rand-int 30))
                      condition (rand-nth ["sunny" "cloudy" "rainy"])]
                  (copilot/result-success
                   (str city ": " temp "°F and " condition))))}))

(copilot/with-client-session [session {:model "gpt-5.2"
                                       :streaming? true
                                       :tools [get-weather]}]
  (println "🌤️  Weather Assistant (type 'exit' to quit)")
  (println "   Try: 'What's the weather in Paris?'")
  (println)
  (loop []
    (print "You: ")
    (flush)
    (let [input (read-line)]
      (when (and input (not= (.toLowerCase input) "exit"))
        (print "🤖: ")
        (println (h/query input :session session))
        (println)
        (recur)))))
```

## Step 6: List Available Models

Discover which models are available and their billing multipliers:

```clojure
(require '[github.copilot-sdk :as copilot])

(copilot/with-client [client]
  (doseq [m (copilot/list-models client)]
    (println (:id m) (str "x" (get-in m [:model-billing :multiplier])))))
;; prints:
;; gpt-5.2 x1.0
;; claude-sonnet-4.5 x1.0
;; o1 x2.0
;; ...
```

Each model map includes `:id`, `:name`, `:vendor`, `:family`, `:max-input-tokens`, `:max-output-tokens`, and nested `:model-capabilities`, `:model-billing`, and `:model-policy` maps. See the [API Reference](./reference/API.md#list-models) for the full structure.

## What's Next?

Now that you have the basics, explore these topics:

- **[API Reference](./reference/API.md)** — Complete API documentation
- **[Authentication](./auth/index.md)** — All auth methods including BYOK
- **[MCP Servers](./mcp/overview.md)** — Connect to external tools via MCP
- **[Examples](../examples/README.md)** — More working examples

### Key Concepts

| Concept | Description |
|---------|-------------|
| **Client** | Manages the connection to the Copilot CLI server |
| **Session** | A conversation with context, model, and tools |
| **Tools** | Functions that Copilot can call in your code |
| **Events** | Streaming updates via core.async channels |
| **Helpers** | High-level stateless API with automatic lifecycle management |

### Comparison with JavaScript SDK

| JavaScript | Clojure |
|------------|---------|
| `new CopilotClient()` | `(copilot/client {})` or `(copilot/with-client ...)` |
| `client.createSession({...})` | `(copilot/create-session client {...})` |
| `session.sendAndWait({prompt})` | `(copilot/send-and-wait! session {:prompt ...})` |
| `session.on("event", handler)` | `(tap (copilot/events session) ch)` |
| `defineTool("name", {...})` | `(copilot/define-tool "name" {...})` |
| `await` / Promises | Blocking calls or `core.async` go blocks |

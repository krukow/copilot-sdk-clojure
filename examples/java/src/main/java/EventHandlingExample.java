import krukow.copilot_sdk.*;
import java.util.*;

/**
 * Event handling example demonstrating different ways to process events.
 * 
 * Shows:
 * 1. Non-streaming mode - only final message events (no deltas)
 * 2. Streaming mode with callbacks (sendStreaming)
 * 3. Streaming mode with pull-based EventSubscription
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class EventHandlingExample {
    
    public static void main(String[] args) {
        System.out.println("=== Event Handling Example ===\n");
        
        ICopilotClient client = Copilot.createClient();
        client.start();
        
        try {
            // Demo 1: Non-streaming - subscribe-events works but no deltas
            System.out.println("--- Non-streaming (no deltas) ---\n");
            demoNonStreaming(client);
            
            // Demo 2: Streaming with callbacks
            System.out.println("\n--- Streaming: Callback-based (sendStreaming) ---\n");
            demoStreamingCallback(client);
            
            // Demo 3: Streaming with pull-based EventSubscription
            System.out.println("\n--- Streaming: Pull-based (EventSubscription) ---\n");
            demoStreamingPullBased(client);
            
        } finally {
            client.stop();
        }
        
        System.out.println("\n=== Done ===");
        System.exit(0);
    }
    
    /**
     * Non-streaming: subscribe-events works, but server only sends final message.
     * Use this when you don't need incremental updates.
     */
    static void demoNonStreaming(ICopilotClient client) {
        SessionOptionsBuilder sb = new SessionOptionsBuilder();
        sb.model("gpt-5.2");
        // Note: streaming(false) is the default - no delta events
        ICopilotSession session = client.createSession((SessionOptions) sb.build());
        
        try {
            String prompt = "What is 1+1? Respond with just the number.";
            System.out.println("Q: " + prompt);
            
            int deltaCount = 0;
            int messageCount = 0;
            
            try (EventSubscription events = session.subscribeEvents()) {
                session.send(prompt);
                
                Event event;
                while ((event = events.take()) != null) {
                    if (event.isMessageDelta()) {
                        deltaCount++;
                        System.out.print(event.getDeltaContent());
                    } else if (event.isMessage()) {
                        messageCount++;
                        System.out.println("A: " + event.getContent());
                    } else if (event.isIdle()) {
                        break;
                    }
                }
            }
            
            System.out.println("Delta events: " + deltaCount + ", Message events: " + messageCount);
        } finally {
            session.destroy();
        }
    }
    
    /**
     * Streaming callback-based: Events are pushed to your handler.
     * Simpler, but less control over the event loop.
     */
    static void demoStreamingCallback(ICopilotClient client) {
        SessionOptionsBuilder sb = new SessionOptionsBuilder();
        sb.model("gpt-5.2");
        sb.streaming(true);  // Enable delta events
        ICopilotSession session = client.createSession((SessionOptions) sb.build());
        
        try {
            int[] deltaCount = {0};
            String prompt = "What is 2+2? Respond with just the number.";
            
            System.out.println("Q: " + prompt);
            System.out.print("A: ");
            
            session.sendStreaming(
                prompt,
                event -> {
                    if (event.isMessageDelta()) {
                        deltaCount[0]++;
                        System.out.print(event.getDeltaContent());
                    } else if (event.isMessage()) {
                        System.out.println();
                    }
                });
            
            System.out.println("Delta events: " + deltaCount[0]);
        } finally {
            session.destroy();
        }
    }
    
    /**
     * Streaming pull-based: You control when to read events.
     * More control, can do work between events, easier to cancel.
     */
    static void demoStreamingPullBased(ICopilotClient client) {
        SessionOptionsBuilder sb = new SessionOptionsBuilder();
        sb.model("gpt-5.2");
        sb.streaming(true);  // Enable delta events
        ICopilotSession session = client.createSession((SessionOptions) sb.build());
        
        try {
            int deltaCount = 0;
            String prompt = "What is 3+3? Respond with just the number.";
            
            System.out.println("Q: " + prompt);
            System.out.print("A: ");
            
            try (EventSubscription events = session.subscribeEvents()) {
                session.send(prompt);
                
                Event event;
                while ((event = events.take()) != null) {
                    if (event.isMessageDelta()) {
                        deltaCount++;
                        System.out.print(event.getDeltaContent());
                    } else if (event.isMessage()) {
                        System.out.println();
                    } else if (event.isIdle()) {
                        break;
                    } else if (event.isError()) {
                        System.err.println("Error: " + event.get("error"));
                        break;
                    }
                }
            }
            
            System.out.println("Delta events: " + deltaCount);
        } finally {
            session.destroy();
        }
    }
}

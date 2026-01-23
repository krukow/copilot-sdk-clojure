import krukow.copilot_sdk.*;
import java.util.*;

/**
 * Event handling example demonstrating different ways to process events.
 * 
 * Shows two approaches:
 * 1. Callback-based with sendStreaming (simpler)
 * 2. Pull-based with EventSubscription (more control)
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class EventHandlingExample {
    
    public static void main(String[] args) {
        System.out.println("=== Event Handling Example ===\n");
        
        ICopilotClient client = Copilot.createClient();
        client.start();
        
        try {
            SessionOptionsBuilder sb = new SessionOptionsBuilder();
            sb.model("gpt-4.1");
            ICopilotSession session = client.createSession((SessionOptions) sb.build());
            
            try {
                // Demo 1: Callback-based approach
                System.out.println("--- Callback-based (sendStreaming) ---\n");
                demoCallbackApproach(session);
                
                // Demo 2: Pull-based approach with EventSubscription
                System.out.println("\n--- Pull-based (EventSubscription) ---\n");
                demoEventSubscription(session);
                
            } finally {
                session.destroy();
            }
            
        } finally {
            client.stop();
        }
        
        System.out.println("\n=== Done ===");
        System.exit(0);
    }
    
    /**
     * Callback-based: Events are pushed to your handler.
     * Simpler, but less control over the event loop.
     */
    static void demoCallbackApproach(ICopilotSession session) {
        int[] deltaCount = {0};
        
        session.sendStreaming(
            "What is 2+2? Respond with just the number.",
            event -> {
                if (event.isMessageDelta()) {
                    deltaCount[0]++;
                    System.out.print(event.getDeltaContent());
                } else if (event.isMessage()) {
                    System.out.println();
                }
            });
        
        System.out.println("Received " + deltaCount[0] + " delta events");
    }
    
    /**
     * Pull-based: You control when to read events.
     * More control, can do work between events, easier to cancel.
     */
    static void demoEventSubscription(ICopilotSession session) {
        int deltaCount = 0;
        
        // EventSubscription is AutoCloseable
        try (EventSubscription events = session.subscribeEvents()) {
            // Send is non-blocking
            session.send("What is 3+3? Respond with just the number.");
            
            // Pull events as needed
            Event event;
            while ((event = events.take()) != null) {
                if (event.isMessageDelta()) {
                    deltaCount++;
                    System.out.print(event.getDeltaContent());
                } else if (event.isMessage()) {
                    System.out.println();
                } else if (event.isIdle()) {
                    break; // Done processing
                } else if (event.isError()) {
                    System.err.println("Error: " + event.get("error"));
                    break;
                }
            }
        }
        
        System.out.println("Received " + deltaCount + " delta events");
    }
}

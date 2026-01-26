import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.ClientOptions;
import krukow.copilot_sdk.ClientOptionsBuilder;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;
import krukow.copilot_sdk.ICopilotClient;
import krukow.copilot_sdk.ICopilotSession;
import java.util.Map;

/**
 * Multi-turn conversation example demonstrating session-based chat.
 * See examples/java/README.md for build and run instructions.
 */
public class ConversationJavaExample {
    
    public static void main(String[] args) {
        System.out.println("=== Multi-turn Conversation ===\n");
        
        // Create client with options
        ClientOptionsBuilder clientBuilder = new ClientOptionsBuilder();
        clientBuilder.logLevel("info");
        ClientOptions clientOpts = (ClientOptions) clientBuilder.build();
        
        ICopilotClient client = Copilot.createClient(clientOpts);
        client.start();
        
        try {
            // Create session
            SessionOptionsBuilder sessionBuilder = new SessionOptionsBuilder();
            sessionBuilder.model("gpt-5.2");
            sessionBuilder.infiniteSessions(Map.of(
                "enabled", true,
                "background-compaction-threshold", 0.80,
                "buffer-exhaustion-threshold", 0.95
            ));
            SessionOptions sessionOpts = (SessionOptions) sessionBuilder.build();
            
            ICopilotSession session = client.createSession(sessionOpts);
            
            try {
                String workspacePath = session.getWorkspacePath();
                if (workspacePath != null) {
                    System.out.println("Workspace: " + workspacePath);
                }

                // First question
                String q1 = "What is the capital of France? Answer in one sentence.";
                System.out.println("Q1: " + q1);
                String a1 = session.sendAndWait(q1, 60000);
                System.out.println("A1: " + a1);
                
                // Follow-up question (context preserved)
                String q2 = "What is its population approximately?";
                System.out.println("\nQ2: " + q2);
                String a2 = session.sendAndWait(q2, 60000);
                System.out.println("A2: " + a2);
                
                // Another follow-up
                String q3 = "What famous tower is located there?";
                System.out.println("\nQ3: " + q3);
                String a3 = session.sendAndWait(q3, 60000);
                System.out.println("A3: " + a3);
                
            } finally {
                session.destroy();
            }
            
        } finally {
            client.stop();
        }
        
        System.out.println("\n=== Done ===");
        // System.exit triggers shutdown hook cleanly (needed for Maven exec:java)
        System.exit(0);
    }
}

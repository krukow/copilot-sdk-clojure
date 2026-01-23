import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;
import krukow.copilot_sdk.ICopilotClient;
import krukow.copilot_sdk.ICopilotSession;

/**
 * Interactive chat example demonstrating a multi-turn conversation
 * with streaming responses displayed in real-time.
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class InteractiveChatExample {
    
    public static void main(String[] args) {
        System.out.println("=== Interactive Streaming Chat ===\n");
        
        ICopilotClient client = Copilot.createClient(null);
        client.start();
        
        try {
            // Create a session with streaming enabled
            SessionOptionsBuilder builder = new SessionOptionsBuilder();
            builder.model("gpt-5.2");
            builder.streaming(true);
            builder.systemPrompt(
                "You are a helpful coding assistant. " +
                "Provide clear, concise answers about programming topics. " +
                "Use examples when helpful.");
            
            ICopilotSession session = client.createSession((SessionOptions) builder.build());
            
            try {
                // Simulate a multi-turn coding conversation
                String[] questions = {
                    "What is a closure in programming? Give a brief explanation.",
                    "Can you show me a simple example in JavaScript?",
                    "How would the same concept look in Java?"
                };
                
                for (int i = 0; i < questions.length; i++) {
                    String question = questions[i];
                    System.out.println("👤 User: " + question);
                    System.out.print("🤖 Assistant: ");
                    
                    // Stream the response
                    session.sendStreaming(question, event -> {
                        if (event.isMessageDelta()) {
                            String delta = event.getDeltaContent();
                            if (delta != null) {
                                System.out.print(delta);
                                System.out.flush();
                            }
                        } else if (event.isMessage()) {
                            System.out.println(); // newline after complete message
                        }
                    });
                    
                    System.out.println(); // extra spacing between turns
                }
                
            } finally {
                session.destroy();
            }
            
        } finally {
            client.stop();
        }
        
        System.out.println("=== Done ===");
        System.exit(0);
    }
}

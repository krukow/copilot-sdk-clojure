import krukow.copilot_sdk.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Parallel queries example using CompletableFuture.
 * 
 * Demonstrates running multiple queries concurrently with the async API.
 * Each query uses its own session for true parallelism.
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class ParallelQueriesExample {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Parallel Queries with CompletableFuture ===\n");
        
        ICopilotClient client = Copilot.createClient();
        client.start();
        
        try {
            String[] questions = {
                "Capital of France? One word.",
                "Largest planet? One word.",
                "Author of Hamlet? One word.",
                "Speed of light in km/s? Just number.",
                "Year WWII ended? Just year."
            };
            
            System.out.println("Sending " + questions.length + " queries in parallel...\n");
            long startTime = System.currentTimeMillis();
            
            // Create session options
            SessionOptionsBuilder sb = new SessionOptionsBuilder();
            sb.model("gpt-4.1");
            SessionOptions opts = (SessionOptions) sb.build();
            
            // Create sessions and futures
            List<ICopilotSession> sessions = new ArrayList<>();
            List<CompletableFuture<String>> futures = new ArrayList<>();
            
            for (int i = 0; i < questions.length; i++) {
                ICopilotSession session = client.createSession(opts);
                sessions.add(session);
                
                String question = questions[i];
                int idx = i + 1;
                
                // sendAsync returns CompletableFuture<String>
                @SuppressWarnings("unchecked")
                CompletableFuture<String> rawFuture = (CompletableFuture<String>) session.sendAsync(question);
                CompletableFuture<String> future = rawFuture
                    .thenApply(answer -> "Q" + idx + ": " + question + "\n   A: " + (answer != null ? answer.trim() : "null"));
                
                futures.add(future);
            }
            
            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // Print results
            System.out.println("Results:");
            System.out.println("-".repeat(50));
            
            for (CompletableFuture<String> f : futures) {
                System.out.println(f.get());
                System.out.println();
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("-".repeat(50));
            System.out.println("Completed " + questions.length + " queries in " + elapsed + "ms");
            
            // Cleanup sessions
            for (ICopilotSession session : sessions) {
                session.destroy();
            }
            
        } finally {
            client.stop();
        }
        
        System.out.println("\n=== Done ===");
        System.exit(0);
    }
}

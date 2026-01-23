import krukow.copilot_sdk.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Multi-agent example demonstrating parallel AI assistants with different roles.
 * 
 * Three phases: Research (parallel), Analysis, Synthesis.
 */
public class MultiAgentExample {
    
    private static final String[] TOPICS = {
        "functional programming benefits",
        "immutable data structures", 
        "concurrent programming challenges"
    };
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Multi-Agent Collaboration ===\n");
        
        ICopilotClient client = Copilot.createClient();
        client.start();
        
        List<ICopilotSession> sessions = new ArrayList<>();
        try {
            String research = researchPhase(client, sessions);
            String analysis = analysisPhase(client, sessions, research);
            String summary = synthesisPhase(client, sessions, research, analysis);
            
            System.out.println("\n" + "=".repeat(50));
            System.out.println("📋 FINAL SUMMARY:");
            System.out.println("=".repeat(50));
            System.out.println(summary);
        } finally {
            sessions.forEach(ICopilotSession::destroy);
            client.stop();
        }
        
        System.out.println("\n=== Done ===");
        System.exit(0);
    }
    
    /** Research phase: query topics in parallel */
    private static String researchPhase(ICopilotClient client, List<ICopilotSession> sessions) {
        System.out.println("📚 Research Phase (parallel):");
        long start = System.currentTimeMillis();
        
        // Launch all research queries in parallel
        Map<String, CompletableFuture<String>> futures = Arrays.stream(TOPICS)
            .collect(LinkedHashMap::new,
                (map, topic) -> {
                    ICopilotSession session = createAgent(client,
                        "You are a research assistant. Respond in 2-3 sentences with key facts only.");
                    sessions.add(session);
                    @SuppressWarnings("unchecked")
                    CompletableFuture<String> f = (CompletableFuture<String>)
                        session.sendAsync("Briefly summarize key points about: " + topic);
                    map.put(topic, f);
                },
                Map::putAll);
        
        // Collect results
        String results = futures.entrySet().stream()
            .map(e -> {
                try {
                    String result = e.getValue().get(60, TimeUnit.SECONDS);
                    System.out.println("  • " + e.getKey() + ": " + truncate(result, 100));
                    return "- " + e.getKey() + ": " + result;
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .collect(Collectors.joining("\n", "", "\n"));
        
        System.out.println("  (completed in " + (System.currentTimeMillis() - start) + "ms)");
        return results;
    }
    
    /** Analysis phase: identify patterns */
    private static String analysisPhase(ICopilotClient client, List<ICopilotSession> sessions, 
            String research) {
        System.out.println("\n🔍 Analysis Phase:");
        
        ICopilotSession analyst = createAgent(client,
            "You are an analytical assistant. Identify patterns and insights. Be concise.");
        sessions.add(analyst);
        
        String analysis = analyst.sendAndWait(
            "Analyze these research findings and identify 2-3 key insights:\n\n" + research, 60000);
        System.out.println("  " + truncate(analysis, 200));
        return analysis;
    }
    
    /** Synthesis phase: create final summary */
    private static String synthesisPhase(ICopilotClient client, List<ICopilotSession> sessions,
            String research, String analysis) {
        System.out.println("\n✍️ Synthesis Phase:");
        
        ICopilotSession writer = createAgent(client,
            "You are a professional writer. Write clear, engaging prose.");
        sessions.add(writer);
        
        return writer.sendAndWait(
            "Write a brief (3-4 sentence) executive summary:\n\n" +
            "RESEARCH:\n" + research + "\nANALYSIS:\n" + analysis, 60000);
    }
    
    private static ICopilotSession createAgent(ICopilotClient client, String systemPrompt) {
        SessionOptionsBuilder sb = new SessionOptionsBuilder();
        sb.model("gpt-4.1");
        sb.systemPrompt(systemPrompt);
        return client.createSession((SessionOptions) sb.build());
    }
    
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        String clean = s.replace("\n", " ").trim();
        return clean.length() <= maxLen ? clean : clean.substring(0, maxLen) + "...";
    }
}

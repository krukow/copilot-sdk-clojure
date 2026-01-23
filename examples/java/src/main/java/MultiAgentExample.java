import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;
import krukow.copilot_sdk.ICopilotClient;
import krukow.copilot_sdk.ICopilotSession;

/**
 * Multi-agent example demonstrating parallel AI assistants with different roles.
 * 
 * This example creates three specialized agents (Researcher, Analyst, Writer)
 * that work together to produce a synthesized output.
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class MultiAgentExample {
    
    public static void main(String[] args) {
        System.out.println("=== Multi-Agent Collaboration ===\n");
        
        ICopilotClient client = Copilot.createClient(null);
        client.start();
        
        try {
            // Create three specialized agents with different system prompts
            ICopilotSession researcher = null;
            ICopilotSession analyst = null;
            ICopilotSession writer = null;
            
            try {
                researcher = createAgent(client, 
                    "You are a research assistant. Gather factual information concisely. " +
                    "Respond in 2-3 sentences with key facts only.");
                
                analyst = createAgent(client,
                    "You are an analytical assistant. Identify patterns and insights " +
                    "from information provided. Be insightful but concise.");
                
                writer = createAgent(client,
                    "You are a professional writer. Synthesize information into clear, " +
                    "engaging prose. Write in a professional but accessible style.");
                
                // Step 1: Research phase - gather information on topics
                String[] topics = {
                    "functional programming benefits",
                    "immutable data structures", 
                    "concurrent programming challenges"
                };
                
                System.out.println("📚 Research Phase:");
                StringBuilder researchResults = new StringBuilder();
                for (String topic : topics) {
                    String research = researcher.sendAndWait(
                        "Briefly research and summarize key points about: " + topic + 
                        ". Keep it to 2-3 sentences.", 60000);
                    System.out.println("  • " + topic + ": " + truncate(research, 100));
                    researchResults.append("- ").append(topic).append(": ").append(research).append("\n");
                }
                
                // Step 2: Analysis phase - identify patterns
                System.out.println("\n🔍 Analysis Phase:");
                String analysis = analyst.sendAndWait(
                    "Analyze these research findings and identify 2-3 key insights or patterns:\n\n" +
                    researchResults.toString(), 60000);
                System.out.println("  " + truncate(analysis, 200));
                
                // Step 3: Synthesis phase - create final output
                System.out.println("\n✍️ Synthesis Phase:");
                String synthesis = writer.sendAndWait(
                    "Based on the following research and analysis, write a brief (3-4 sentence) " +
                    "executive summary:\n\nRESEARCH:\n" + researchResults.toString() +
                    "\n\nANALYSIS:\n" + analysis, 60000);
                
                System.out.println("\n" + "=".repeat(50));
                System.out.println("📋 FINAL SUMMARY:");
                System.out.println("=".repeat(50));
                System.out.println(synthesis);
                
            } finally {
                // Clean up all sessions (null-safe)
                if (writer != null) writer.destroy();
                if (analyst != null) analyst.destroy();
                if (researcher != null) researcher.destroy();
            }
            
        } finally {
            client.stop();
        }
        
        System.out.println("\n=== Done ===");
        System.exit(0);
    }
    
    private static ICopilotSession createAgent(ICopilotClient client, String systemPrompt) {
        SessionOptionsBuilder builder = new SessionOptionsBuilder();
        builder.model("gpt-5.2");
        builder.systemPrompt(systemPrompt);
        return client.createSession((SessionOptions) builder.build());
    }
    
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        String clean = s.replace("\n", " ").trim();
        if (clean.length() <= maxLen) return clean;
        return clean.substring(0, maxLen) + "...";
    }
}

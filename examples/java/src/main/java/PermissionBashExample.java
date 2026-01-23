import krukow.copilot_sdk.*;
import java.util.*;

/**
 * Permission handling example demonstrating how to approve/deny shell commands.
 * 
 * This example shows how to:
 * - Set up a permission handler for shell tool invocations
 * - Approve specific commands while denying others
 * - Inspect the permission request payload
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class PermissionBashExample {
    
    public static void main(String[] args) {
        System.out.println("=== Permission Handling Example ===\n");
        
        // Determine shell tool based on OS
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
        String shellTool = isWindows ? "powershell" : "bash";
        String allowedCommand = isWindows 
            ? "Write-Output 'hello from powershell'" 
            : "echo 'hello from bash'";
        String deniedCommand = allowedCommand + " && echo 'this will be denied'";
        
        // Set of commands we'll approve
        Set<String> allowedCommands = Set.of(allowedCommand);
        
        ICopilotClient client = Copilot.createClient(null);
        client.start();
        
        try {
            // Create session with permission handler
            SessionOptionsBuilder builder = new SessionOptionsBuilder();
            builder.model("gpt-5.2");
            builder.allowedTool(shellTool);  // Only allow the shell tool
            builder.onPermissionRequest(request -> {
                // Print the permission request for inspection
                System.out.println("  [Permission Request]");
                String fullCommand = (String) request.get("full-command-text");
                String kind = (String) request.get("kind");
                String intention = (String) request.get("intention");
                System.out.println("    Kind: " + kind);
                System.out.println("    Full command: " + fullCommand);
                System.out.println("    Intention: " + intention);
                
                if (allowedCommands.contains(fullCommand)) {
                    System.out.println("    Decision: APPROVED\n");
                    return PermissionResult.approved();
                } else {
                    System.out.println("    Decision: DENIED\n");
                    // Return denied with the rule that was violated
                    return PermissionResult.deniedByRules(List.of(
                        Map.of("kind", "shell", "argument", fullCommand)
                    ));
                }
            });
            
            ICopilotSession session = client.createSession((SessionOptions) builder.build());
            
            try {
                // Test 1: Run an allowed command
                System.out.println("Test 1: Running ALLOWED command");
                System.out.println("Command: " + allowedCommand);
                String prompt1 = "Run this command with the " + shellTool + 
                    " tool, then reply with just DONE:\n\n" + allowedCommand;
                String response1 = session.sendAndWait(prompt1, 60000);
                System.out.println("Response: " + response1);
                System.out.println();
                
                // Test 2: Run a denied command
                System.out.println("Test 2: Running DENIED command");
                System.out.println("Command: " + deniedCommand);
                String prompt2 = "Run this command with the " + shellTool + 
                    " tool, then reply with just DONE:\n\n" + deniedCommand;
                String response2 = session.sendAndWait(prompt2, 60000);
                System.out.println("Response: " + response2);
                
            } finally {
                session.destroy();
            }
            
        } finally {
            client.stop();
        }
        
        System.out.println("\n=== Done ===");
        System.exit(0);
    }
}
